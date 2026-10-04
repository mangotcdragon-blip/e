import { React, ReactNative as RN } from "@vendetta/metro/common";
import { findByProps } from "@vendetta/metro";
import { storage } from "@vendetta/plugin";
import { useProxy } from "@vendetta/storage";
import { getAssetIDByName } from "@vendetta/ui/assets";

import {
    DEFAULTS,
    FPS_PRESETS,
    LIMITS,
    RESOLUTION_PRESETS,
    clamp,
    describeTarget,
    getTarget,
    initStorage,
    parseNumber,
} from "./config";

// Discord's current design-system components. Looked up once at module load so
// a missing export fails at settings-open time instead of at plugin load.
const DS: any =
    findByProps("TableSwitchRow", "TableRowGroup", "TableRow", "Stack") ?? {};
const {
    TableRowGroup,
    TableRow,
    TableSwitchRow,
    TableRadioGroup,
    TableRadioRow,
    Stack,
    TextInput: DSTextInput,
} = DS;

const CUSTOM = "custom";

type Field = "resolution" | "fps" | "width" | "height";

function NumberField(props: {
    label: string;
    field: Field;
    min: number;
    max: number;
    placeholder?: string;
}) {
    const { label, field, min, max, placeholder } = props;
    const [text, setText] = React.useState(String(storage[field] ?? ""));
    const [error, setError] = React.useState<string | null>(null);

    // Keep the box in sync when a preset row changes the stored value.
    React.useEffect(() => {
        const current = String(storage[field] ?? "");
        if (current !== text && parseNumber(text) !== storage[field]) setText(current);
    }, [storage[field]]);

    const commit = (value: string) => {
        setText(value);
        const n = parseNumber(value);
        if (n === null) {
            setError("Enter a whole number");
            return;
        }
        if (n < min || n > max) {
            setError(`Must be between ${min} and ${max}`);
            return;
        }
        setError(null);
        storage[field] = n;
    };

    const Input = DSTextInput ?? RN.TextInput;
    const inputProps = DSTextInput
        ? {
              label,
              value: text,
              onChange: commit,
              placeholder,
              keyboardType: "number-pad",
              isClearable: true,
              errorMessage: error ?? undefined,
              state: error ? "error" : undefined,
          }
        : {
              value: text,
              onChangeText: commit,
              placeholder,
              keyboardType: "number-pad",
              style: {
                  borderWidth: 1,
                  borderColor: error ? "#f23f43" : "#4e5058",
                  borderRadius: 8,
                  padding: 10,
                  color: "#ffffff",
              },
          };

    return (
        <RN.View style={{ paddingHorizontal: 12, paddingVertical: 6 }}>
            {!DSTextInput && (
                <RN.Text style={{ color: "#b5bac1", marginBottom: 4 }}>{label}</RN.Text>
            )}
            <Input {...inputProps} />
            {!DSTextInput && error && (
                <RN.Text style={{ color: "#f23f43", marginTop: 4 }}>{error}</RN.Text>
            )}
        </RN.View>
    );
}

/**
 * Radio list of preset values plus a "Custom" entry. Falls back to plain rows
 * with a checkmark when the build has no TableRadioGroup.
 */
function PresetPicker(props: {
    title: string;
    field: "resolution" | "fps";
    presets: number[];
    format: (n: number) => string;
    customOpen: boolean;
    setCustomOpen: (open: boolean) => void;
}) {
    const { title, field, presets, format, customOpen, setCustomOpen } = props;
    const current = Number(storage[field]);
    const matchesPreset = presets.includes(current);
    const selected = customOpen || !matchesPreset ? CUSTOM : String(current);

    const choose = (value: string) => {
        if (value === CUSTOM) {
            setCustomOpen(true);
            return;
        }
        setCustomOpen(false);
        storage[field] = Number(value);
    };

    const rows = [
        ...presets.map((n) => ({ value: String(n), label: format(n) })),
        { value: CUSTOM, label: "Custom", subLabel: "Type any value you like" },
    ];

    if (TableRadioGroup && TableRadioRow) {
        return (
            <TableRadioGroup title={title} value={selected} onChange={choose}>
                {rows.map((row) => (
                    <TableRadioRow key={row.value} label={row.label} subLabel={row.subLabel} value={row.value} />
                ))}
            </TableRadioGroup>
        );
    }

    return (
        <TableRowGroup title={title}>
            {rows.map((row) => (
                <TableRow
                    key={row.value}
                    label={row.label}
                    subLabel={row.subLabel}
                    onPress={() => choose(row.value)}
                    trailing={
                        selected === row.value ? (
                            <RN.Image
                                source={getAssetIDByName("CheckmarkSmallIcon") || getAssetIDByName("Check")}
                                style={{ width: 20, height: 20, tintColor: "#23a55a" }}
                            />
                        ) : null
                    }
                />
            ))}
        </TableRowGroup>
    );
}

export default function Settings() {
    useProxy(storage);
    initStorage();

    const [resCustom, setResCustom] = React.useState(!RESOLUTION_PRESETS.includes(Number(storage.resolution)));
    const [fpsCustom, setFpsCustom] = React.useState(!FPS_PRESETS.includes(Number(storage.fps)));

    if (!TableRowGroup || !TableRow || !TableSwitchRow) {
        return (
            <RN.ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 16 }}>
                <RN.Text style={{ color: "#ffffff" }}>
                    This Discord build is missing the components the settings page needs. The plugin still
                    applies the saved values: {describeTarget()}.
                </RN.Text>
            </RN.ScrollView>
        );
    }

    const target = getTarget();
    const Wrap = Stack ?? RN.View;

    return (
        <RN.ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 10, paddingBottom: 48 }}>
            <Wrap spacing={12}>
                <TableRowGroup title="Stream Quality">
                    <TableSwitchRow
                        label="Override screen share quality"
                        subLabel="Replace Discord's resolution and frame rate with the values below"
                        value={!!storage.enabled}
                        onValueChange={(v: boolean) => (storage.enabled = v)}
                    />
                    <TableRow
                        label="Current target"
                        subLabel={`${describeTarget(target)}. Takes effect the next time you start sharing.`}
                    />
                </TableRowGroup>

                <PresetPicker
                    title="Resolution"
                    field="resolution"
                    presets={RESOLUTION_PRESETS}
                    format={(n) => `${n}p`}
                    customOpen={resCustom}
                    setCustomOpen={setResCustom}
                />
                {(resCustom || !RESOLUTION_PRESETS.includes(Number(storage.resolution))) && !storage.useExactSize && (
                    <NumberField
                        label="Custom resolution (short side, px)"
                        field="resolution"
                        min={LIMITS.resolution.min}
                        max={LIMITS.resolution.max}
                        placeholder={String(DEFAULTS.resolution)}
                    />
                )}

                <PresetPicker
                    title="Frame rate"
                    field="fps"
                    presets={FPS_PRESETS}
                    format={(n) => `${n} fps`}
                    customOpen={fpsCustom}
                    setCustomOpen={setFpsCustom}
                />
                {(fpsCustom || !FPS_PRESETS.includes(Number(storage.fps))) && (
                    <NumberField
                        label="Custom frame rate"
                        field="fps"
                        min={LIMITS.fps.min}
                        max={LIMITS.fps.max}
                        placeholder={String(DEFAULTS.fps)}
                    />
                )}

                <TableRowGroup title="Exact dimensions">
                    <TableSwitchRow
                        label="Set width and height myself"
                        subLabel="Ignore the resolution above and the screen's aspect ratio"
                        value={!!storage.useExactSize}
                        onValueChange={(v: boolean) => (storage.useExactSize = v)}
                    />
                </TableRowGroup>
                {storage.useExactSize && (
                    <Wrap spacing={4}>
                        <NumberField
                            label="Width (px)"
                            field="width"
                            min={LIMITS.side.min}
                            max={LIMITS.side.max}
                            placeholder={String(DEFAULTS.width)}
                        />
                        <NumberField
                            label="Height (px)"
                            field="height"
                            min={LIMITS.side.min}
                            max={LIMITS.side.max}
                            placeholder={String(DEFAULTS.height)}
                        />
                    </Wrap>
                )}

                <TableRowGroup title="Advanced">
                    <TableSwitchRow
                        label="Force encoder options"
                        subLabel="Also rewrite the numbers right before they reach the video encoder"
                        value={!!storage.patchEncoder}
                        onValueChange={(v: boolean) => (storage.patchEncoder = v)}
                    />
                    <TableSwitchRow
                        label="Custom preset flag"
                        subLabel="Tell Discord the stream uses a custom preset so a quality preset can't override it"
                        value={!!storage.customPreset}
                        onValueChange={(v: boolean) => (storage.customPreset = v)}
                    />
                    <TableSwitchRow
                        label="Override stream settings store"
                        subLabel="Try this if the quality doesn't change. Discord's own quality picker may show odd values."
                        value={!!storage.patchStore}
                        onValueChange={(v: boolean) => (storage.patchStore = v)}
                    />
                    <TableSwitchRow
                        label="Debug logging"
                        subLabel="Log every patched call to the debug console"
                        value={!!storage.debug}
                        onValueChange={(v: boolean) => (storage.debug = v)}
                    />
                    <TableRow
                        label="Note"
                        subLabel="Encoder, store and preset switches apply after the plugin is reloaded. Resolution and frame rate apply on the next share."
                    />
                </TableRowGroup>
            </Wrap>
        </RN.ScrollView>
    );
}
