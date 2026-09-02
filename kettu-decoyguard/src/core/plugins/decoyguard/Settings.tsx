import { ScrollView, View } from "react-native";
import { TableRow, TableRowGroup, TextArea } from "@metro/common/components";
import { showInputAlert } from "@core/vendetta/alerts";

import { decoyGuardStorage } from "./storage";
import { isLocked, setLocked, useLocked } from "./state";
import { lockDecoyChannels, unlockDecoyChannels } from "./guard";

export default function DecoyGuardSettings() {
    const locked = useLocked();
    const [json, setJson] = React.useState(() => JSON.stringify(decoyGuardStorage.channels, null, 2));
    const [error, setError] = React.useState("");

    function saveJson() {
        try {
            const parsed = JSON.parse(json);
            decoyGuardStorage.channels = parsed;
            setError("");
            if (isLocked()) lockDecoyChannels();
        } catch (e) {
            setError("Invalid JSON: " + (e as Error).message);
        }
    }

    return (
        <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 12 }}>
            <TableRowGroup title="Status">
                <TableRow
                    label={locked ? "Locked - showing decoys" : "Unlocked - showing real DMs"}
                    subLabel="Always starts locked when the app opens or reloads. Auto re-locks when the app is backgrounded."
                />
                <TableRow
                    label={locked ? "Force unlock now" : "Force re-lock now"}
                    onPress={() => {
                        if (locked) {
                            setLocked(false);
                            unlockDecoyChannels();
                        } else {
                            setLocked(true);
                            lockDecoyChannels();
                        }
                    }}
                />
            </TableRowGroup>

            <TableRowGroup title="Unlock phrase">
                <TableRow
                    label="Change unlock phrase"
                    subLabel="Type this into any DM box and hit send while locked to unlock (it's intercepted, never actually sent). Send it again while unlocked to re-lock."
                    onPress={() => showInputAlert({
                        title: "Unlock phrase",
                        placeholder: "your secret phrase",
                        initialValue: decoyGuardStorage.unlockPhrase,
                        confirmText: "Save",
                        onConfirm: (value: string) => {
                            const trimmed = value.trim();
                            if (!trimmed) throw new Error("Can't be empty");
                            decoyGuardStorage.unlockPhrase = trimmed;
                        },
                    })}
                />
            </TableRowGroup>

            <TableRowGroup title="Decoy channels">
                <View style={{ padding: 12, gap: 8 }}>
                    <TableRow
                        label="Format"
                        subLabel={'{ "CHANNEL_ID": { "label": "whatever, just for your reference", "messages": [ { "fromMe": true, "text": "lol yeah" }, { "fromMe": false, "text": "haha same" } ] } }'}
                    />
                    <TextArea
                        value={json}
                        onChange={(v: string | { text: string }) => setJson(typeof v === "string" ? v : v.text)}
                        placeholder="{}"
                        multiline
                        numberOfLines={16}
                    />
                    {!!error && <TableRow label={error} />}
                </View>
                <TableRow label="Save decoy config" onPress={saveJson} />
            </TableRowGroup>
        </ScrollView>
    );
}
