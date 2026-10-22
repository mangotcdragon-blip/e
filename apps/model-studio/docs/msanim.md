# The `.msanim` animation format

`.msanim` is Model Studio's own animation file. It is plain JSON, so it is easy to read, diff and
generate from other tools, and it maps one-to-one onto Minecraft (Bedrock / GeckoLib) and Blockbench
animations, and onto glTF animations.

A file holds one or more **clips**. Each clip has **tracks**; a track animates one **channel**
(`rotation`, `position` or `scale`) of one **bone** (any named part of the model) with **keyframes**.

```json
{
	"format": "msanim",
	"formatVersion": 1,
	"generator": "Model Studio",
	"model": "robot",
	"rotationOrder": "ZYX",
	"units": { "time": "seconds", "rotation": "degrees", "position": "model units", "scale": "multiplier" },
	"clips": [
		{
			"name": "walk",
			"length": 1,
			"fps": 24,
			"loop": "loop",
			"tracks": [
				{
					"bone": "leg_left",
					"channel": "rotation",
					"keys": [
						{ "t": 0, "v": [30, 0, 0], "i": "smooth" },
						{ "t": 0.5, "v": [-30, 0, 0], "i": "smooth" },
						{ "t": 1, "v": [30, 0, 0] }
					]
				}
			]
		}
	]
}
```

## Fields

| Field | Meaning |
| --- | --- |
| `format` | Always `"msanim"`. This is how the file is recognised, whatever its extension. |
| `formatVersion` | `1`. Readers should refuse versions newer than they know. |
| `model` | Optional: the model the animation was made for. Informational only. |
| `rotationOrder` | Always `"ZYX"` in version 1 (the order Minecraft and Blockbench use). |
| `clips[].name` | Clip name. |
| `clips[].length` | Length in seconds. |
| `clips[].fps` | Frame rate the clip was authored at. Keyframes snap to it in the editor and glTF export samples at it. |
| `clips[].loop` | `"loop"`, `"once"` (play once; in Minecraft the parts then return to rest) or `"hold"` (play once and stay on the last frame). |
| `tracks[].bone` | Name of the part to move. Matched against the names of the model's bones/groups/objects. |
| `tracks[].channel` | `"rotation"`, `"position"` or `"scale"`. |
| `keys[].t` | Time in seconds. |
| `keys[].v` | `[x, y, z]`. See below. A single number means the same value on all three axes. |
| `keys[].i` | How to get from this key to the next: `"linear"` (default), `"smooth"` (Catmull-Rom) or `"step"` (hold, then jump). |

## Values are offsets from the rest pose

* **rotation**: degrees added to the part's rest rotation, applied in Z, Y, X order.
* **position**: model units added to the part's rest position. For Minecraft models one unit is one pixel (1/16 of a block).
* **scale**: multiplied with the part's rest scale (`[1, 1, 1]` = unchanged).

So an empty clip leaves the model in its normal pose, and the same file works on any model that has
parts with the same names.

Axes follow Blockbench's convention (Minecraft Java style). Converting to Bedrock's `.animation.json`
negates rotation X and Y and position X; Model Studio does this for you in both directions.

## Conversions in Model Studio

| From / to | Notes |
| --- | --- |
| Minecraft Bedrock / GeckoLib `.animation.json` | Both ways. `smooth` becomes `"lerp_mode": "catmullrom"`, `step` becomes `pre`/`post` pairs. Molang expressions are baked into keyframes on import. |
| Blockbench `.bbmodel` | Import (animations inside the project). Blockbench can import the exported `.animation.json`. |
| glTF / GLB, FBX, Collada | Import of node animations (shape-key animations are skipped); export as GLB with baked animations. |
