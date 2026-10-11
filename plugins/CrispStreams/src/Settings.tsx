import { ReactNative as RN } from "@vendetta/metro/common";
import { findByProps } from "@vendetta/metro";
import { storage } from "@vendetta/plugin";
import { useProxy } from "@vendetta/storage";
import { getAssetIDByName } from "@vendetta/ui/assets";

import { SIZES, SizeKey } from "./config";
import { refresh } from "./index";

const DS: any = findByProps("TableSwitchRow", "TableRowGroup", "TableRow", "Stack") ?? {};
const { TableRowGroup, TableRow, TableSwitchRow, TableRadioGroup, TableRadioRow, Stack } = DS;

function set(key: string, value: any) {
    storage[key] = value;
    refresh();
}

function SizePicker() {
    const keys = Object.keys(SIZES) as SizeKey[];
    const current = (storage.size as string) in SIZES ? storage.size : "2160";
    const title = "Request streams at";

    if (TableRadioGroup && TableRadioRow) {
        return (
            <TableRadioGroup title={title} value={current} onChange={(v: string) => set("size", v)}>
                {keys.map((k) => (
                    <TableRadioRow
                        key={k}
                        label={SIZES[k].label}
                        subLabel={`${SIZES[k].width}x${SIZES[k].height}`}
                        value={k}
                    />
                ))}
            </TableRadioGroup>
        );
    }
    return (
        <TableRowGroup title={title}>
            {keys.map((k) => (
                <TableRow
                    key={k}
                    label={SIZES[k].label}
                    subLabel={`${SIZES[k].width}x${SIZES[k].height}`}
                    onPress={() => set("size", k)}
                    trailing={
                        current === k ? (
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

    if (!TableRowGroup || !TableRow || !TableSwitchRow) {
        return (
            <RN.ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 16 }}>
                <RN.Text style={{ color: "#ffffff" }}>
                    This Discord build is missing the components the settings page needs. The plugin still works with
                    its saved settings.
                </RN.Text>
            </RN.ScrollView>
        );
    }

    const Wrap = Stack ?? RN.View;

    return (
        <RN.ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 10, paddingBottom: 48 }}>
            <Wrap spacing={12}>
                <TableRowGroup title="Crisp Streams">
                    <TableSwitchRow
                        label="Full quality streams"
                        subLabel="Ask for the full stream instead of one sized to the small video tile"
                        value={!!storage.enabled}
                        onValueChange={(v: boolean) => set("enabled", v)}
                    />
                    <TableSwitchRow
                        label="Prefer the best layer"
                        subLabel="When a stream is sent in several qualities, watch the highest one"
                        value={!!storage.preferBestLayer}
                        onValueChange={(v: boolean) => set("preferBestLayer", v)}
                    />
                </TableRowGroup>

                <SizePicker />

                <TableRowGroup title="Advanced">
                    <TableSwitchRow
                        label="Debug logging"
                        subLabel="Log every rewritten request to the debug console"
                        value={!!storage.debug}
                        onValueChange={(v: boolean) => (storage.debug = v)}
                    />
                    <TableRow
                        label="Note"
                        subLabel="The stream can't be sharper than what the streamer sends. Higher settings use more mobile data."
                    />
                </TableRowGroup>
            </Wrap>
        </RN.ScrollView>
    );
}
