import { React, ReactNative, NavigationNative } from "@vendetta/metro/common";
import { findByStoreName } from "@vendetta/metro";
import { storage } from "@vendetta/plugin";
import { useProxy } from "@vendetta/storage";
import { Forms } from "@vendetta/ui/components";
import { showConfirmationAlert } from "@vendetta/ui/alerts";
import { showToast } from "@vendetta/ui/toasts";
import { getAssetIDByName } from "@vendetta/ui/assets";
import * as store from "./store";
import { exportChannel, fmtTime, RANGES } from "./export";

const { ScrollView, View, Text } = ReactNative;
const { FormSection, FormRow, FormSwitchRow, FormInput, FormDivider, FormRadioRow } = Forms;

const GuildStore = findByStoreName("GuildStore");

const icon = name => <FormRow.Icon source={getAssetIDByName(name)} />;

function useStoreVersion() {
    const [, setV] = React.useState(0);
    React.useEffect(() => store.subscribe(() => setV(v => v + 1)), []);
}

function ChannelPage({ channelId }) {
    useProxy(storage);
    useStoreVersion();
    const navigation = NavigationNative.useNavigation();
    const meta = store.getIndex()[channelId] ?? {};
    const [range, setRange] = React.useState("all");
    const [user, setUser] = React.useState("");
    const [deletedOnly, setDeletedOnly] = React.useState(false);
    const [savedPath, setSavedPath] = React.useState(null);
    const opts = { range, user, deletedOnly };
    storage.ignoredChannels ??= {};

    return (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={{ paddingBottom: 48 }}>
            <FormSection title={`${meta.guildName ?? ""} ${meta.name ?? channelId}`.trim()}>
                <FormRow
                    label={`${meta.count ?? 0} messages logged`}
                    subLabel={meta.last ? `Latest: ${fmtTime(meta.last)}` : undefined}
                    leading={icon("ic_message")}
                />
            </FormSection>

            <FormSection title="Export range">
                {Object.entries(RANGES).map(([key, r]) => (
                    <FormRadioRow key={key} label={r.label} selected={range === key} onPress={() => setRange(key)} />
                ))}
            </FormSection>

            <FormSection title="Filters">
                <FormInput
                    title="Only this user (ID, username or part of a name)"
                    placeholder="Leave empty for everyone"
                    value={user}
                    onChange={setUser}
                />
                <FormSwitchRow
                    label="Deleted messages only"
                    value={deletedOnly}
                    onValueChange={setDeletedOnly}
                />
            </FormSection>

            <FormSection title="Export as .txt">
                <FormRow
                    label="Share / save .txt"
                    subLabel="Opens the share sheet (Files, Drive, another app...)"
                    leading={icon("ic_share")}
                    onPress={() => exportChannel(channelId, opts, "share")}
                />
                <FormDivider />
                <FormRow
                    label="Copy to clipboard"
                    leading={icon("ic_copy_message_link")}
                    onPress={() => exportChannel(channelId, opts, "copy")}
                />
                <FormDivider />
                <FormRow
                    label="Save file only"
                    subLabel={savedPath ?? "Saved inside Discord's app storage"}
                    leading={icon("ic_download_24px")}
                    onPress={async () => setSavedPath(await exportChannel(channelId, opts, "save") ?? null)}
                />
            </FormSection>

            <FormSection title="Manage">
                <FormSwitchRow
                    label="Stop logging this channel"
                    value={!!storage.ignoredChannels[channelId]}
                    onValueChange={v => {
                        if (v) storage.ignoredChannels[channelId] = true;
                        else delete storage.ignoredChannels[channelId];
                    }}
                />
                <FormDivider />
                <FormRow
                    label="Delete this channel's log"
                    leading={icon("ic_message_delete")}
                    onPress={() => showConfirmationAlert({
                        title: "Delete log?",
                        content: `This permanently deletes ${meta.count ?? 0} logged messages for ${meta.name ?? channelId}.`,
                        confirmText: "Delete",
                        confirmColor: "red",
                        cancelText: "Cancel",
                        onConfirm: async () => {
                            await store.clearChannel(channelId);
                            showToast("Log deleted", getAssetIDByName("Check"));
                            navigation.goBack();
                        },
                    })}
                />
            </FormSection>
        </ScrollView>
    );
}

function ServersPage() {
    useProxy(storage);
    storage.selectedGuilds ??= {};
    storage.ignoredGuilds ??= {};
    const [query, setQuery] = React.useState("");
    const selectedMode = storage.guildMode === "selected";
    const guilds = Object.values(GuildStore.getGuilds?.() ?? {})
        .filter(g => !query || g.name.toLowerCase().includes(query.toLowerCase()))
        .sort((a, b) => a.name.localeCompare(b.name));

    return (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={{ paddingBottom: 48 }}>
            <FormSection title={selectedMode ? "Log only these servers" : "Servers to log (turn off to skip one)"}>
                <FormInput placeholder="Search servers" value={query} onChange={setQuery} />
                {guilds.map(g => {
                    const on = selectedMode ? !!storage.selectedGuilds[g.id] : !storage.ignoredGuilds[g.id];
                    return (
                        <FormSwitchRow
                            key={g.id}
                            label={g.name}
                            value={on}
                            onValueChange={v => {
                                if (selectedMode) {
                                    if (v) storage.selectedGuilds[g.id] = true;
                                    else delete storage.selectedGuilds[g.id];
                                } else {
                                    if (v) delete storage.ignoredGuilds[g.id];
                                    else storage.ignoredGuilds[g.id] = true;
                                }
                            }}
                        />
                    );
                })}
            </FormSection>
        </ScrollView>
    );
}

export default function Settings() {
    useProxy(storage);
    useStoreVersion();
    const navigation = NavigationNative.useNavigation();
    const [query, setQuery] = React.useState("");

    const push = (title, render) => navigation.push("VendettaCustomPage", { title, render });

    const q = query.trim().toLowerCase();
    const entries = Object.entries(store.getIndex())
        .filter(([id, m]) => !q || id.includes(q)
            || (m.name ?? "").toLowerCase().includes(q)
            || (m.guildName ?? "").toLowerCase().includes(q))
        .sort(([, a], [, b]) => (b.last ?? 0) - (a.last ?? 0));
    const total = entries.reduce((n, [, m]) => n + (m.count ?? 0), 0);

    return (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={{ paddingBottom: 48 }}>
            <FormSection title="Logging">
                <FormSwitchRow
                    label="Logging enabled"
                    leading={icon("ic_message_edit")}
                    value={!!storage.enabled}
                    onValueChange={v => (storage.enabled = v)}
                />
                <FormSwitchRow
                    label="Log every server"
                    subLabel={storage.guildMode === "selected"
                        ? "Off: only servers you pick below are logged"
                        : "On: all servers except ones you turn off below"}
                    value={storage.guildMode !== "selected"}
                    onValueChange={v => (storage.guildMode = v ? "all" : "selected")}
                />
                <FormRow
                    label="Choose servers"
                    leading={icon("ic_guild")}
                    trailing={FormRow.Arrow ? <FormRow.Arrow /> : undefined}
                    onPress={() => push("Servers", ServersPage)}
                />
                <FormSwitchRow
                    label="Log DMs and group DMs"
                    value={!!storage.logDMs}
                    onValueChange={v => (storage.logDMs = v)}
                />
                <FormSwitchRow
                    label="Log bot messages"
                    value={!!storage.logBots}
                    onValueChange={v => (storage.logBots = v)}
                />
                <FormSwitchRow
                    label="Also log history you scroll through"
                    subLabel="Captures older messages when you open a channel"
                    value={!!storage.logHistory}
                    onValueChange={v => (storage.logHistory = v)}
                />
                <FormInput
                    title="Max messages kept per channel (0 = unlimited)"
                    keyboardType="numeric"
                    value={String(storage.maxPerChannel ?? "")}
                    onChange={v => (storage.maxPerChannel = parseInt(v, 10) || 0)}
                />
            </FormSection>

            <FormSection title={`Logged channels (${entries.length}, ${total} messages)`}>
                <FormInput placeholder="Search channel or server" value={query} onChange={setQuery} />
                {entries.length === 0 && (
                    <View style={{ padding: 16 }}>
                        <Text style={{ color: "#999" }}>
                            Nothing logged yet. Messages appear here as they arrive.
                        </Text>
                    </View>
                )}
                {entries.map(([id, m]) => (
                    <FormRow
                        key={id}
                        label={m.name ?? id}
                        subLabel={`${m.guildName ? `${m.guildName} · ` : ""}${m.count ?? 0} msgs${m.last ? ` · ${fmtTime(m.last)}` : ""}`}
                        trailing={FormRow.Arrow ? <FormRow.Arrow /> : undefined}
                        onPress={() => push(m.name ?? id, () => <ChannelPage channelId={id} />)}
                    />
                ))}
            </FormSection>

            <FormSection title="Danger zone">
                <FormRow
                    label="Delete ALL logs"
                    leading={icon("ic_message_delete")}
                    onPress={() => showConfirmationAlert({
                        title: "Delete all logs?",
                        content: `This permanently deletes ${total} logged messages across ${entries.length} channels.`,
                        confirmText: "Delete everything",
                        confirmColor: "red",
                        cancelText: "Cancel",
                        onConfirm: async () => {
                            await store.clearAll();
                            showToast("All logs deleted", getAssetIDByName("Check"));
                        },
                    })}
                />
            </FormSection>
        </ScrollView>
    );
}
