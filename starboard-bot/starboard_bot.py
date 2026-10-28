#!/usr/bin/env python3
"""
Starboard bot: one file, one job.

Every starboard watches one or more channels for one emoji. When a message
collects enough of that emoji, the bot posts it to the starboard channel as an
embed (author, full text, jump link, and every attachment), reacts to that
post with the same emoji, and keeps the count in the post up to date. Stars on
the original message and on the starboard post are added together, and each
person counts once. You can run as many starboards as you like, each with its
own emoji, channels and threshold.

Everything is configured in a web dashboard; there are no slash commands.

Setup
-----
1. pip install -U discord.py
2. In the Discord developer portal (https://discord.com/developers/applications)
   create a bot, copy its token, and under "Bot" turn on
   "MESSAGE CONTENT INTENT".
3. Run:
       BOT_TOKEN=your-token DASHBOARD_PASSWORD=pick-one python starboard_bot.py
   (On Windows PowerShell: $env:BOT_TOKEN="..."; $env:DASHBOARD_PASSWORD="..."; python starboard_bot.py)
4. Open http://localhost:8080, log in, use "Invite bot" to add it to your
   server, then click "New starboard".

Optional settings (environment variables):
    DASHBOARD_PORT   port for the dashboard (default 8080)
    DASHBOARD_HOST   address to listen on (default 0.0.0.0)
    DATA_FILE        where settings and posts are saved (default starboard_data.json
                     next to this file)

If DASHBOARD_PASSWORD is not set, a password is generated on first run,
saved in the data file, and printed to the console.
"""

import asyncio
import hashlib
import hmac
import io
import json
import logging
import os
import re
import secrets
import uuid
from pathlib import Path

import discord
from aiohttp import web

BOT_TOKEN = os.getenv("BOT_TOKEN", "")
DASHBOARD_PASSWORD = os.getenv("DASHBOARD_PASSWORD", "")
DASHBOARD_HOST = os.getenv("DASHBOARD_HOST", "0.0.0.0")
DASHBOARD_PORT = int(os.getenv("DASHBOARD_PORT", "8080"))
DATA_FILE = Path(os.getenv("DATA_FILE", Path(__file__).with_name("starboard_data.json")))

# View Channel, Send Messages, Send Messages in Threads, Embed Links,
# Attach Files, Read Message History, Add Reactions.
INVITE_PERMISSIONS = 1024 | 2048 | 274877906944 | 16384 | 32768 | 65536 | 64

BOARD_DEFAULTS = {
    "name": "Starboard",
    "enabled": True,
    "guild_id": "",
    "emoji": "⭐",
    "threshold": 3,
    "source_channels": [],
    "target_channel": "",
    "auto_react": True,
    "self_star": False,
    "remove_below": False,
    "color": "#f5b301",
}

SOURCE_TYPES = {
    discord.ChannelType.text: "text",
    discord.ChannelType.news: "news",
    discord.ChannelType.forum: "forum",
}

log = logging.getLogger("starboard")


# --------------------------------------------------------------------------
# Storage
# --------------------------------------------------------------------------

class Store:
    """Settings and starboard posts, saved to one JSON file.

    IDs are kept as strings so the dashboard's JavaScript never rounds them.
    """

    def __init__(self, path: Path):
        self.path = path
        self.data = {"secret": secrets.token_hex(32), "password": "", "boards": {}, "posts": {}}
        if path.exists():
            self.data.update(json.loads(path.read_text("utf-8")))
        self.boards: dict = self.data["boards"]
        # posts[board_id][original_message_id] = {sb_msg, sb_channel, orig_channel}
        self.posts: dict = self.data["posts"]
        self.by_sb_msg: dict[int, tuple[str, int]] = {}
        for bid, posts in self.posts.items():
            for orig, p in posts.items():
                self.by_sb_msg[p["sb_msg"]] = (bid, int(orig))
        self.save()

    def save(self):
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=2, ensure_ascii=False), "utf-8")
        os.replace(tmp, self.path)

    def add_post(self, bid: str, orig_id: int, post: dict):
        self.posts.setdefault(bid, {})[str(orig_id)] = post
        self.by_sb_msg[post["sb_msg"]] = (bid, orig_id)
        self.save()

    def drop_post(self, bid: str, orig_id: int):
        post = self.posts.get(bid, {}).pop(str(orig_id), None)
        if post:
            self.by_sb_msg.pop(post["sb_msg"], None)
            self.save()

    def delete_board(self, bid: str):
        self.boards.pop(bid, None)
        for post in self.posts.pop(bid, {}).values():
            self.by_sb_msg.pop(post["sb_msg"], None)
        self.save()


def emoji_key(emoji) -> str:
    """Comparable key for a unicode emoji, a custom emoji, or its string form."""
    if isinstance(emoji, str):
        emoji = discord.PartialEmoji.from_str(emoji.strip())
    if getattr(emoji, "id", None):
        return str(emoji.id)
    return (getattr(emoji, "name", None) or str(emoji)).replace("️", "")


def watches(board: dict, channel) -> bool:
    """Whether a channel (or a thread/forum post inside it) is one the board listens in."""
    sources = board["source_channels"]
    return str(channel.id) in sources or str(getattr(channel, "parent_id", None)) in sources


def clean_board(raw: dict, existing: dict | None) -> dict:
    b = dict(BOARD_DEFAULTS)
    b.update(existing or {})
    for key in BOARD_DEFAULTS:
        if key in raw:
            b[key] = raw[key]
    b["name"] = str(b["name"]).strip()[:80] or "Starboard"
    for key in ("enabled", "auto_react", "self_star", "remove_below"):
        b[key] = bool(b[key])
    try:
        b["threshold"] = max(1, min(10000, int(b["threshold"])))
    except (TypeError, ValueError):
        b["threshold"] = BOARD_DEFAULTS["threshold"]
    b["guild_id"] = str(b["guild_id"])
    b["target_channel"] = str(b["target_channel"] or "")
    b["source_channels"] = list(dict.fromkeys(str(c) for c in b["source_channels"] if c))[:200]
    emoji = discord.PartialEmoji.from_str(str(b["emoji"]).strip() or BOARD_DEFAULTS["emoji"])
    b["emoji"] = str(emoji) if emoji.id else emoji.name
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", str(b["color"])):
        b["color"] = BOARD_DEFAULTS["color"]
    return b


# --------------------------------------------------------------------------
# Bot
# --------------------------------------------------------------------------

class StarboardBot(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.store = Store(DATA_FILE)
        self.locks: dict[tuple[str, int], asyncio.Lock] = {}

    async def setup_hook(self):
        await start_dashboard(self)

    async def on_ready(self):
        log.info("Logged in as %s in %d server(s)", self.user, len(self.guilds))

    # ---- helpers ---------------------------------------------------------

    def boards_in(self, guild_id) -> list[tuple[str, dict]]:
        gid = str(guild_id)
        return [(bid, b) for bid, b in self.store.boards.items() if b["enabled"] and b["guild_id"] == gid]

    def lock(self, bid: str, message_id: int) -> asyncio.Lock:
        return self.locks.setdefault((bid, message_id), asyncio.Lock())

    async def resolve_channel(self, guild, channel_id):
        channel_id = int(channel_id)
        channel = guild.get_channel_or_thread(channel_id) if guild else None
        if channel is None:
            try:
                channel = await self.fetch_channel(channel_id)
            except discord.HTTPException:
                return None
        return channel

    async def fetch(self, guild, channel_id, message_id):
        channel = await self.resolve_channel(guild, channel_id)
        if channel is None:
            return None
        try:
            return await channel.fetch_message(int(message_id))
        except discord.HTTPException:
            return None

    # ---- events ----------------------------------------------------------

    async def on_message(self, message: discord.Message):
        if not message.guild or message.author.id == self.user.id:
            return
        if message.type not in (discord.MessageType.default, discord.MessageType.reply):
            return
        for _, b in self.boards_in(message.guild.id):
            if not b["auto_react"] or str(message.channel.id) == b["target_channel"]:
                continue
            if watches(b, message.channel):
                try:
                    await message.add_reaction(discord.PartialEmoji.from_str(b["emoji"]))
                except discord.HTTPException as e:
                    log.warning("Could not react in #%s: %s", message.channel, e)

    async def on_raw_reaction_add(self, p: discord.RawReactionActionEvent):
        if p.user_id != self.user.id:
            await self.handle_reaction(p.guild_id, p.channel_id, p.message_id, p.emoji)

    async def on_raw_reaction_remove(self, p: discord.RawReactionActionEvent):
        if p.user_id != self.user.id:
            await self.handle_reaction(p.guild_id, p.channel_id, p.message_id, p.emoji)

    async def on_raw_reaction_clear_emoji(self, p: discord.RawReactionClearEmojiEvent):
        await self.handle_reaction(p.guild_id, p.channel_id, p.message_id, p.emoji)

    async def on_raw_reaction_clear(self, p: discord.RawReactionClearEvent):
        await self.handle_reaction(p.guild_id, p.channel_id, p.message_id, None)

    async def on_raw_message_edit(self, p: discord.RawMessageUpdateEvent):
        for bid, posts in list(self.store.posts.items()):
            post = posts.get(str(p.message_id))
            if post:
                await self.update_embed(bid, p.message_id, post)

    async def on_raw_message_delete(self, p: discord.RawMessageDeleteEvent):
        # Someone deleted a starboard post by hand: forget it so it can be reposted.
        hit = self.store.by_sb_msg.get(p.message_id)
        if hit:
            self.store.drop_post(*hit)

    # ---- starboard logic -------------------------------------------------

    async def handle_reaction(self, guild_id, channel_id, message_id, emoji):
        if not guild_id:
            return
        key = emoji_key(emoji) if emoji is not None else None

        # A reaction on a starboard post counts toward the original message.
        hit = self.store.by_sb_msg.get(message_id)
        if hit:
            bid, orig_id = hit
            b = self.store.boards.get(bid)
            if b and b["enabled"] and (key is None or emoji_key(b["emoji"]) == key):
                post = self.store.posts[bid][str(orig_id)]
                await self.refresh(bid, post["orig_channel"], orig_id)
            return

        boards = [(bid, b) for bid, b in self.boards_in(guild_id) if key is None or emoji_key(b["emoji"]) == key]
        if not boards:
            return
        channel = await self.resolve_channel(self.get_guild(guild_id), channel_id)
        if channel is None:
            return
        for bid, b in boards:
            if watches(b, channel) and str(channel.id) != b["target_channel"]:
                await self.refresh(bid, channel_id, message_id)

    async def count(self, b: dict, message: discord.Message, sb_msg) -> int:
        key = emoji_key(b["emoji"])
        users = set()
        for msg in (message, sb_msg):
            if msg is None:
                continue
            for reaction in msg.reactions:
                if emoji_key(reaction.emoji) != key:
                    continue
                async for user in reaction.users(limit=None):
                    if user.bot or (not b["self_star"] and user.id == message.author.id):
                        continue
                    users.add(user.id)
        return len(users)

    @staticmethod
    def header(b: dict, count: int, message: discord.Message) -> str:
        return f"{b['emoji']} **{count}** | {message.channel.mention}"

    async def refresh(self, bid: str, channel_id, message_id: int):
        async with self.lock(bid, int(message_id)):
            try:
                await self._refresh(bid, channel_id, int(message_id))
            except Exception:
                log.exception("Failed to update starboard for message %s", message_id)

    async def _refresh(self, bid: str, channel_id, message_id: int):
        b = self.store.boards.get(bid)
        guild = self.get_guild(int(b["guild_id"])) if b else None
        if not guild:
            return
        message = await self.fetch(guild, channel_id, message_id)
        if message is None:
            return

        post = self.store.posts.get(bid, {}).get(str(message_id))
        sb_msg = await self.fetch(guild, post["sb_channel"], post["sb_msg"]) if post else None
        if post and sb_msg is None:
            self.store.drop_post(bid, message_id)

        count = await self.count(b, message, sb_msg)
        header = self.header(b, count, message)

        if count >= b["threshold"]:
            if sb_msg:
                if sb_msg.content != header:
                    await sb_msg.edit(content=header)
                return
            if not b["target_channel"]:
                return
            target = await self.resolve_channel(guild, b["target_channel"])
            if target is None:
                return
            files, images, links = await self.gather_files(message, guild.filesize_limit)
            sb_msg = await target.send(content=header, embeds=self.make_embeds(b, message, images, links), files=files)
            self.store.add_post(bid, message_id, {
                "sb_msg": sb_msg.id,
                "sb_channel": sb_msg.channel.id,
                "orig_channel": message.channel.id,
            })
            try:
                await sb_msg.add_reaction(discord.PartialEmoji.from_str(b["emoji"]))
            except discord.HTTPException:
                pass
        elif sb_msg:
            if b["remove_below"]:
                self.store.drop_post(bid, message_id)
                await sb_msg.delete()
            elif sb_msg.content != header:
                await sb_msg.edit(content=header)

    async def gather_files(self, message: discord.Message, limit: int):
        """Download the attachments so they survive in the starboard post.

        Returns (files, names of up to 4 images to show inside the embed,
        links for anything too large to re-upload).
        """
        files, images, links, total = [], [], [], 0
        for i, a in enumerate(message.attachments):
            spoiler = a.is_spoiler()
            base = a.filename[len("SPOILER_"):] if spoiler else a.filename
            name = f"{'SPOILER_' if spoiler else ''}{i}_{base}"
            if total + a.size > limit:
                links.append(f"[{a.filename}]({a.url})")
                continue
            try:
                data = await a.read()
            except discord.HTTPException:
                links.append(f"[{a.filename}]({a.url})")
                continue
            total += a.size
            files.append(discord.File(io.BytesIO(data), filename=name))
            if not spoiler and (a.content_type or "").startswith("image/") and len(images) < 4:
                images.append(name)
        return files, images, links

    @staticmethod
    def make_embeds(b: dict, message: discord.Message, images: list[str], links: list[str]) -> list[discord.Embed]:
        jump = f"**[Click to jump to message!]({message.jump_url})**"
        text = message.content
        if not text and message.embeds:
            text = message.embeds[0].description or message.embeds[0].title or ""
        limit = 4096 - len(jump) - 2
        if len(text) > limit:
            text = text[: limit - 1] + "…"
        description = f"{text}\n\n{jump}" if text else jump

        embed = discord.Embed(
            description=description,
            color=int(b["color"][1:], 16),
            timestamp=message.created_at,
            url=message.jump_url,
        )
        embed.set_author(name=message.author.display_name, icon_url=message.author.display_avatar.url)
        embed.set_footer(text=f"#{message.channel.name}")
        if links:
            value = "\n".join(links)
            embed.add_field(name="Attachments", value=value if len(value) <= 1024 else value[:1021] + "...")

        if images:
            embed.set_image(url=f"attachment://{images[0]}")
        elif message.embeds:
            # Link previews / gifs: reuse their picture.
            e = message.embeds[0]
            url = (e.image and e.image.url) or (e.thumbnail and e.thumbnail.url)
            if url:
                embed.set_image(url=url)
        if message.stickers:
            if not embed.image:
                embed.set_image(url=message.stickers[0].url)

        embeds = [embed]
        # Extra embeds with the same url show up as one image gallery.
        for name in images[1:]:
            embeds.append(discord.Embed(url=message.jump_url).set_image(url=f"attachment://{name}"))
        return embeds

    async def update_embed(self, bid: str, orig_id: int, post: dict):
        """Original message was edited: rebuild the embed text, keep its files."""
        async with self.lock(bid, orig_id):
            b = self.store.boards.get(bid)
            guild = self.get_guild(int(b["guild_id"])) if b else None
            if not guild:
                return
            message = await self.fetch(guild, post["orig_channel"], orig_id)
            sb_msg = await self.fetch(guild, post["sb_channel"], post["sb_msg"])
            if message is None or sb_msg is None:
                return
            uploaded, images = set(), []
            for a in sb_msg.attachments:
                spoiler = a.filename.startswith("SPOILER_")
                index = a.filename[8 if spoiler else 0:].split("_", 1)[0]
                if index.isdigit():
                    uploaded.add(int(index))
                if not spoiler and (a.content_type or "").startswith("image/") and len(images) < 4:
                    images.append(a.filename)
            links = [f"[{a.filename}]({a.url})" for i, a in enumerate(message.attachments) if i not in uploaded]
            embeds = self.make_embeds(b, message, images, links)
            if sb_msg.embeds and sb_msg.embeds[0].description == embeds[0].description:
                return
            try:
                await sb_msg.edit(embeds=embeds)
            except discord.HTTPException as e:
                log.warning("Could not update starboard post %s: %s", sb_msg.id, e)

    # ---- dashboard data --------------------------------------------------

    def guild_info(self, guild: discord.Guild) -> dict:
        channels = []
        for category, chans in guild.by_category():
            for c in chans:
                kind = SOURCE_TYPES.get(c.type)
                if kind:
                    channels.append({
                        "id": str(c.id),
                        "name": c.name,
                        "type": kind,
                        "category": category.name if category else "",
                    })
        return {
            "id": str(guild.id),
            "name": guild.name,
            "icon": guild.icon.url if guild.icon else "",
            "channels": channels,
            "emojis": [{"str": str(e), "name": e.name} for e in guild.emojis if e.available],
        }

    def warnings(self, b: dict) -> list[str]:
        guild = self.get_guild(int(b["guild_id"])) if b["guild_id"].isdigit() else None
        if guild is None:
            return ["The bot is not in this server."]
        me, out = guild.me, []

        def missing(channel, needed):
            perms = channel.permissions_for(me)
            return [label for attr, label in needed if not getattr(perms, attr)]

        if not b["source_channels"]:
            out.append("Pick at least one channel to listen in.")
        if not b["target_channel"]:
            out.append("Pick a channel to post starred messages in.")
        else:
            target = guild.get_channel(int(b["target_channel"]))
            if target is None:
                out.append("The starboard channel was deleted. Pick another one.")
            else:
                gaps = missing(target, [
                    ("view_channel", "View Channel"), ("send_messages", "Send Messages"),
                    ("embed_links", "Embed Links"), ("attach_files", "Attach Files"),
                    ("add_reactions", "Add Reactions"), ("read_message_history", "Read Message History"),
                ])
                if gaps:
                    out.append(f"Bot is missing in #{target.name}: {', '.join(gaps)}.")
        needed = [("view_channel", "View Channel"), ("read_message_history", "Read Message History")]
        if b["auto_react"]:
            needed.append(("add_reactions", "Add Reactions"))
        for cid in b["source_channels"]:
            channel = guild.get_channel(int(cid))
            if channel is None:
                out.append("One of the listened channels was deleted. Untick it and save.")
                continue
            gaps = missing(channel, needed)
            if gaps:
                out.append(f"Bot is missing in #{channel.name}: {', '.join(gaps)}.")
        if b["target_channel"] and b["target_channel"] in b["source_channels"]:
            out.append("The starboard channel is also a listened channel; messages there are ignored.")
        emoji = discord.PartialEmoji.from_str(b["emoji"])
        if emoji.id and self.get_emoji(emoji.id) is None:
            out.append("The bot can't use that custom emoji. Use one from a server the bot is in.")
        return out

    def boards_payload(self) -> dict:
        return {bid: {**b, "warnings": self.warnings(b)} for bid, b in self.store.boards.items()}


# --------------------------------------------------------------------------
# Dashboard
# --------------------------------------------------------------------------

def dashboard_password(bot: StarboardBot) -> str:
    if DASHBOARD_PASSWORD:
        return DASHBOARD_PASSWORD
    if not bot.store.data.get("password"):
        bot.store.data["password"] = secrets.token_urlsafe(9)
        bot.store.save()
    return bot.store.data["password"]


async def start_dashboard(bot: StarboardBot):
    password = dashboard_password(bot)
    token = hmac.new(bot.store.data["secret"].encode(), password.encode(), hashlib.sha256).hexdigest()

    @web.middleware
    async def auth(request: web.Request, handler):
        if request.path == "/login":
            return await handler(request)
        if not hmac.compare_digest(request.cookies.get("sb_auth", ""), token):
            if request.path.startswith("/api/"):
                raise web.HTTPUnauthorized()
            raise web.HTTPFound("/login")
        return await handler(request)

    async def login_page(request: web.Request):
        error = ""
        if request.method == "POST":
            form = await request.post()
            if hmac.compare_digest(str(form.get("password", "")), password):
                resp = web.HTTPFound("/")
                resp.set_cookie("sb_auth", token, max_age=60 * 60 * 24 * 30, httponly=True, samesite="Lax")
                raise resp
            error = '<p class="err">Wrong password.</p>'
        return web.Response(text=LOGIN_HTML.replace("{{error}}", error), content_type="text/html")

    async def logout(request: web.Request):
        resp = web.HTTPFound("/login")
        resp.del_cookie("sb_auth")
        raise resp

    async def index(request: web.Request):
        return web.Response(text=DASHBOARD_HTML, content_type="text/html")

    async def state(request: web.Request):
        invite = ""
        if bot.user:
            invite = discord.utils.oauth_url(bot.user.id, permissions=discord.Permissions(INVITE_PERMISSIONS))
        return web.json_response({
            "ready": bot.is_ready(),
            "bot": str(bot.user) if bot.user else "",
            "invite": invite,
            "guilds": [bot.guild_info(g) for g in sorted(bot.guilds, key=lambda g: g.name.lower())],
            "boards": bot.boards_payload(),
        })

    async def save_board(request: web.Request):
        raw = await request.json()
        bid = request.match_info.get("bid")
        if bid and bid not in bot.store.boards:
            raise web.HTTPNotFound()
        if not bid:
            if not str(raw.get("guild_id", "")).isdigit():
                raise web.HTTPBadRequest(text="guild_id required")
            bid = uuid.uuid4().hex[:12]
        if bid in bot.store.boards:
            raw.pop("guild_id", None)
        bot.store.boards[bid] = clean_board(raw, bot.store.boards.get(bid))
        bot.store.save()
        return web.json_response({"id": bid, "boards": bot.boards_payload()})

    async def delete_board(request: web.Request):
        bot.store.delete_board(request.match_info["bid"])
        return web.json_response({"boards": bot.boards_payload()})

    app = web.Application(middlewares=[auth])
    app.add_routes([
        web.get("/", index),
        web.get("/login", login_page),
        web.post("/login", login_page),
        web.get("/logout", logout),
        web.get("/api/state", state),
        web.post("/api/boards", save_board),
        web.post("/api/boards/{bid}", save_board),
        web.delete("/api/boards/{bid}", delete_board),
    ])
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    await web.TCPSite(runner, DASHBOARD_HOST, DASHBOARD_PORT).start()
    shown = "localhost" if DASHBOARD_HOST in ("0.0.0.0", "::") else DASHBOARD_HOST
    log.info("Dashboard running at http://%s:%d", shown, DASHBOARD_PORT)
    if not DASHBOARD_PASSWORD:
        log.info("Dashboard password: %s  (set DASHBOARD_PASSWORD to choose your own)", password)


LOGIN_HTML = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Starboard login</title>
<style>
  body{margin:0;min-height:100vh;display:grid;place-items:center;background:#1e1f22;color:#dbdee1;
       font:15px/1.4 system-ui,-apple-system,"Segoe UI",sans-serif}
  form{background:#2b2d31;padding:28px;border-radius:12px;width:min(320px,90vw);box-shadow:0 8px 24px #0006}
  h1{margin:0 0 16px;font-size:20px;color:#f2f3f5}
  input{width:100%;box-sizing:border-box;padding:10px 12px;border-radius:6px;border:1px solid #1e1f22;
        background:#1e1f22;color:#f2f3f5;font-size:15px}
  button{margin-top:12px;width:100%;padding:10px;border:0;border-radius:6px;background:#5865f2;color:#fff;
         font-size:15px;font-weight:600;cursor:pointer}
  .err{color:#f23f43;margin:10px 0 0}
</style></head><body>
<form method="post" action="/login">
  <h1>Starboard dashboard</h1>
  <input type="password" name="password" placeholder="Password" autofocus required>
  <button>Log in</button>
  {{error}}
</form></body></html>"""


DASHBOARD_HTML = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Starboard dashboard</title>
<style>
  :root{--bg:#1e1f22;--panel:#2b2d31;--panel2:#313338;--line:#3f4147;--text:#dbdee1;--muted:#949ba4;
        --bright:#f2f3f5;--blurple:#5865f2;--green:#23a55a;--red:#da373c;--yellow:#f0b232}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
  header{position:sticky;top:0;z-index:5;display:flex;flex-wrap:wrap;gap:10px;align-items:center;
         padding:12px 16px;background:var(--panel);border-bottom:1px solid var(--line)}
  header h1{font-size:18px;margin:0 auto 0 0;color:var(--bright)}
  main{max-width:900px;margin:0 auto;padding:16px}
  select,input[type=text],input[type=number],input:not([type]){background:var(--bg);color:var(--bright);
        border:1px solid var(--line);border-radius:6px;padding:8px 10px;font-size:15px;width:100%}
  button,.btn{border:0;border-radius:6px;padding:9px 14px;font-size:14px;font-weight:600;cursor:pointer;
        background:var(--panel2);color:var(--bright);text-decoration:none;display:inline-block}
  .primary{background:var(--blurple);color:#fff}
  .danger{background:transparent;color:#f87171;border:1px solid #f8717155}
  .ghost{background:transparent;color:var(--muted)}
  #guild{width:auto;min-width:180px}
  .card{background:var(--panel);border-radius:12px;padding:18px;margin:0 0 16px;border:1px solid var(--line)}
  .card.off{opacity:.65}
  .top{display:flex;gap:12px;align-items:center;margin-bottom:14px}
  .top .name{font-size:17px;font-weight:600}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:14px}
  .field{margin-bottom:14px}
  .field>label,.label{display:block;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.03em;
        color:var(--muted);margin-bottom:6px}
  .hint{font-weight:400;text-transform:none;letter-spacing:0}
  .emoji-row{display:flex;gap:8px;align-items:center}
  .emoji-preview{font-size:28px;width:44px;height:44px;display:grid;place-items:center;background:var(--bg);
        border-radius:8px;flex:none}
  .emoji-preview img,.pick img{width:28px;height:28px;object-fit:contain}
  .picks{display:flex;flex-wrap:wrap;gap:4px;margin-top:6px;max-height:120px;overflow:auto}
  .pick{background:var(--panel2);padding:4px 6px;font-size:20px;line-height:1;border-radius:6px}
  .pick img{width:22px;height:22px}
  .chips{display:flex;flex-wrap:wrap;gap:6px;max-height:190px;overflow:auto;padding:2px}
  .chip{background:var(--panel2);color:var(--text);font-weight:500;border:1px solid var(--line);padding:6px 10px;
        border-radius:999px}
  .chip.on{background:#5865f233;border-color:var(--blurple);color:var(--bright)}
  .chip .cat{color:var(--muted);font-size:12px;margin-left:4px}
  .filter{margin-bottom:8px}
  .toggles{display:flex;flex-direction:column;gap:10px;margin:4px 0 14px}
  .toggle{display:flex;gap:10px;align-items:flex-start;cursor:pointer}
  .toggle small{display:block;color:var(--muted)}
  .switch{position:relative;width:40px;height:24px;flex:none}
  .switch input{opacity:0;width:0;height:0;position:absolute}
  .switch span{position:absolute;inset:0;background:#80848e;border-radius:999px;transition:.15s}
  .switch span:before{content:"";position:absolute;width:18px;height:18px;left:3px;top:3px;background:#fff;
        border-radius:50%;transition:.15s}
  .switch input:checked+span{background:var(--green)}
  .switch input:checked+span:before{transform:translateX(16px)}
  .warn{background:#f0b2321a;border-left:3px solid var(--yellow);padding:8px 12px;border-radius:6px;margin-bottom:12px;
        font-size:14px}
  .warn div+div{margin-top:4px}
  .actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
  .status{color:var(--green);font-size:14px}
  .empty{text-align:center;color:var(--muted);padding:40px 16px;background:var(--panel);border-radius:12px}
  .preview{font-size:13px;color:var(--muted);margin-top:4px}
  input[type=color]{width:44px;height:38px;padding:0;border:1px solid var(--line);border-radius:6px;background:var(--bg)}
</style></head><body>
<header>
  <h1>Starboards</h1>
  <select id="guild" aria-label="Server"></select>
  <a class="btn" id="invite" target="_blank" rel="noopener">Invite bot</a>
  <a class="btn ghost" href="/logout">Log out</a>
</header>
<main>
  <div id="boards"></div>
  <button class="primary" id="add" style="width:100%;padding:12px">+ New starboard</button>
</main>
<script>
const QUICK = ["⭐","🌟","🔥","💀","😂","❤️","👍","🏆","💯","👏","🤣","😭","💎","🤡","📸","🎨"];
let S = {guilds: [], boards: {}};
let gid = "";
try { gid = localStorage.getItem("sb_guild") || ""; } catch (e) {}

const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

function emojiHtml(s) {
  const m = /^<(a?):(\w+):(\d+)>$/.exec(s || "");
  if (m) return `<img alt=":${esc(m[2])}:" src="https://cdn.discordapp.com/emojis/${m[3]}.${m[1] ? "gif" : "png"}?size=48">`;
  return esc(s || "");
}

async function api(method, url, body) {
  const r = await fetch(url, {method, headers: {"Content-Type": "application/json"}, body: body && JSON.stringify(body)});
  if (r.status === 401) { location = "/login"; throw new Error("logged out"); }
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

async function load() {
  S = await api("GET", "/api/state");
  if (!S.ready && !S.guilds.length) { setTimeout(load, 2000); }
  const inv = $("#invite");
  inv.href = S.invite || "#";
  inv.style.display = S.invite ? "" : "none";
  if (!S.guilds.find(g => g.id === gid)) gid = S.guilds[0] ? S.guilds[0].id : "";
  $("#guild").innerHTML = S.guilds.map(g => `<option value="${g.id}" ${g.id === gid ? "selected" : ""}>${esc(g.name)}</option>`).join("");
  render();
}

function guild() { return S.guilds.find(g => g.id === gid); }

function render() {
  const box = $("#boards");
  const g = guild();
  $("#add").style.display = g ? "" : "none";
  if (!g) {
    box.innerHTML = `<div class="empty">${S.ready ? "The bot isn't in any server yet. Click <b>Invite bot</b> above, add it to your server, then refresh this page." : "Connecting to Discord..."}</div>`;
    return;
  }
  const ids = Object.keys(S.boards).filter(id => S.boards[id].guild_id === gid);
  box.innerHTML = ids.length ? ids.map(id => card(id, S.boards[id], g)).join("")
    : `<div class="empty">No starboards in <b>${esc(g.name)}</b> yet. Click <b>+ New starboard</b> to make one.</div>`;
}

function toggle(cls, on, title, sub) {
  return `<label class="toggle"><span class="switch"><input type="checkbox" class="${cls}" ${on ? "checked" : ""}><span></span></span>
    <span>${title}<small>${sub}</small></span></label>`;
}

function card(id, b, g) {
  const targets = g.channels.filter(c => c.type !== "forum");
  const picks = QUICK.map(e => `<button type="button" class="pick" data-e="${esc(e)}">${e}</button>`)
    .concat(g.emojis.map(e => `<button type="button" class="pick" title=":${esc(e.name)}:" data-e="${esc(e.str)}">${emojiHtml(e.str)}</button>`)).join("");
  const chips = g.channels.map(c => `<button type="button" class="chip ${b.source_channels.includes(c.id) ? "on" : ""}" data-ch="${c.id}"
      data-q="${esc((c.name + " " + c.category).toLowerCase())}">${c.type === "forum" ? "Forum: " : "#"}${esc(c.name)}${c.category ? `<span class="cat">${esc(c.category)}</span>` : ""}</button>`).join("");
  const warn = b.warnings && b.warnings.length ? `<div class="warn">${b.warnings.map(w => `<div>${esc(w)}</div>`).join("")}</div>` : "";
  return `<section class="card ${b.enabled ? "" : "off"}" data-id="${id}">
    <div class="top">
      <input class="name" value="${esc(b.name)}" placeholder="Starboard name" aria-label="Name">
      <label class="toggle" title="On / off"><span class="switch"><input type="checkbox" class="enabled" ${b.enabled ? "checked" : ""}><span></span></span></label>
    </div>
    ${warn}
    <div class="grid">
      <div class="field"><label>Emoji</label>
        <div class="emoji-row"><span class="emoji-preview">${emojiHtml(b.emoji)}</span>
          <input class="emoji" value="${esc(b.emoji)}" placeholder="Paste or pick an emoji"></div>
        <div class="picks">${picks}</div>
      </div>
      <div>
        <div class="field"><label>Reactions needed</label><input type="number" min="1" class="threshold" value="${b.threshold}"></div>
        <div class="field"><label>Post starred messages in</label>
          <select class="target"><option value="">Choose a channel...</option>
          ${targets.map(c => `<option value="${c.id}" ${c.id === b.target_channel ? "selected" : ""}>#${esc(c.name)}</option>`).join("")}
          </select></div>
      </div>
    </div>
    <div class="field"><label>Listen in these channels <span class="hint">(tap to select; forum = every post in it)</span></label>
      <input class="filter" placeholder="Search channels...">
      <div class="chips">${chips}</div>
    </div>
    <div class="toggles">
      ${toggle("auto_react", b.auto_react, "Auto-react", "The bot reacts with the emoji on every new message in the listened channels.")}
      ${toggle("self_star", b.self_star, "Allow self-star", "Count the author's own reaction on their message.")}
      ${toggle("remove_below", b.remove_below, "Remove when it drops below", "Delete the starboard post if the count falls under the limit again.")}
    </div>
    <div class="field" style="display:flex;gap:10px;align-items:center"><input type="color" class="color" value="${b.color}">
      <span class="label" style="margin:0">Embed color</span></div>
    <div class="actions">
      <button class="primary save">Save</button>
      <button class="danger del">Delete</button>
      <span class="status"></span>
    </div>
  </section>`;
}

function collect(el) {
  return {
    name: $(".name", el).value,
    enabled: $(".enabled", el).checked,
    emoji: $(".emoji", el).value,
    threshold: +$(".threshold", el).value || 1,
    target_channel: $(".target", el).value,
    source_channels: [...el.querySelectorAll(".chip.on")].map(c => c.dataset.ch),
    auto_react: $(".auto_react", el).checked,
    self_star: $(".self_star", el).checked,
    remove_below: $(".remove_below", el).checked,
    color: $(".color", el).value,
  };
}

function dirty(el) { $(".status", el).textContent = "Unsaved changes"; $(".status", el).style.color = "var(--yellow)"; }

const boardsEl = $("#boards");
boardsEl.addEventListener("click", async ev => {
  const el = ev.target.closest(".card");
  if (!el) return;
  const id = el.dataset.id;
  const pick = ev.target.closest(".pick");
  const chip = ev.target.closest(".chip");
  if (pick) {
    $(".emoji", el).value = pick.dataset.e;
    $(".emoji-preview", el).innerHTML = emojiHtml(pick.dataset.e);
    dirty(el);
  } else if (chip) {
    chip.classList.toggle("on");
    dirty(el);
  } else if (ev.target.closest(".save")) {
    try {
      const res = await api("POST", "/api/boards/" + id, collect(el));
      S.boards = res.boards;
      el.outerHTML = card(id, S.boards[id], guild());
      const fresh = boardsEl.querySelector(`[data-id="${id}"]`);
      $(".status", fresh).textContent = "Saved";
      setTimeout(() => { const s = $(".status", fresh); if (s) s.textContent = ""; }, 2500);
    } catch (e) { $(".status", el).textContent = "Save failed: " + e.message; $(".status", el).style.color = "#f87171"; }
  } else if (ev.target.closest(".del")) {
    if (!confirm(`Delete "${S.boards[id].name}"? Posts already on the starboard stay, but stop updating.`)) return;
    S.boards = (await api("DELETE", "/api/boards/" + id)).boards;
    render();
  }
});
boardsEl.addEventListener("input", ev => {
  const el = ev.target.closest(".card");
  if (!el) return;
  if (ev.target.classList.contains("filter")) {
    const q = ev.target.value.toLowerCase();
    el.querySelectorAll(".chip").forEach(c => c.style.display = c.dataset.q.includes(q) ? "" : "none");
    return;
  }
  if (ev.target.classList.contains("emoji")) $(".emoji-preview", el).innerHTML = emojiHtml(ev.target.value.trim());
  if (ev.target.classList.contains("enabled")) el.classList.toggle("off", !ev.target.checked);
  dirty(el);
});
boardsEl.addEventListener("change", ev => { const el = ev.target.closest(".card"); if (el && !ev.target.classList.contains("filter")) dirty(el); });

$("#guild").addEventListener("change", e => {
  gid = e.target.value;
  try { localStorage.setItem("sb_guild", gid); } catch (err) {}
  render();
});
$("#add").addEventListener("click", async () => {
  const n = Object.values(S.boards).filter(b => b.guild_id === gid).length + 1;
  const res = await api("POST", "/api/boards", {guild_id: gid, name: n === 1 ? "Starboard" : "Starboard " + n});
  S.boards = res.boards;
  render();
  const el = boardsEl.querySelector(`[data-id="${res.id}"]`);
  if (el) el.scrollIntoView({behavior: "smooth", block: "center"});
});

load().catch(e => { $("#boards").innerHTML = `<div class="empty">Could not load: ${esc(e.message)}</div>`; });
</script>
</body></html>"""


def main():
    if not BOT_TOKEN:
        raise SystemExit("Set the BOT_TOKEN environment variable to your bot token (see the top of this file).")
    StarboardBot().run(BOT_TOKEN, root_logger=True)


if __name__ == "__main__":
    main()
