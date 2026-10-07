import { React, ReactNative as RN } from "@vendetta/metro/common";
import { findByProps } from "@vendetta/metro";
import { storage } from "@vendetta/plugin";
import { useProxy } from "@vendetta/storage";
import { Forms } from "@vendetta/ui/components";

import { translate } from "./translate";

// Discord's current design-system components, with the legacy Forms as fallback.
const DS: any = findByProps("TableSwitchRow", "TableRowGroup", "TableRow", "Stack") ?? {};
const { TableRowGroup, TableRow, TableSwitchRow, Stack } = DS;
const { FormSection, FormSwitchRow, FormRow } = Forms as any;

const Group = TableRowGroup ?? FormSection;
const Row = TableRow ?? FormRow;

function Switch(props: { label: string; subLabel: string; field: string }) {
    const { label, subLabel, field } = props;
    const onValueChange = (v: boolean) => (storage[field] = v);
    if (TableSwitchRow) {
        return <TableSwitchRow label={label} subLabel={subLabel} value={!!storage[field]} onValueChange={onValueChange} />;
    }
    return <FormSwitchRow label={label} subLabel={subLabel} value={!!storage[field]} onValueChange={onValueChange} />;
}

const SAMPLE = "Hey, are you coming? I don't know what you mean lol";

export default function Settings() {
    useProxy(storage);
    const [text, setText] = React.useState(SAMPLE);
    const Wrap = Stack ?? RN.View;

    return (
        <RN.ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 10, paddingBottom: 48 }}>
            <Wrap spacing={12}>
                <Group title="Early Modern English">
                    <Switch label="Rewrite my messages" subLabel="Translate everything you send" field="enabled" />
                    <Switch label="Rewrite edits too" subLabel="Also translate a message when you edit it" field="edits" />
                    <Switch
                        label="Conjugate verbs"
                        subLabel='"thou knowest", "he liketh", "I know not"'
                        field="verbs"
                    />
                    <Switch label="Translate slang" subLabel="lol, idk, u, bro, brb, swearing and insults" field="slang" />
                    <Row
                        label="Send one message unchanged"
                        subLabel="Start it with a backslash (\). The backslash is removed before sending."
                    />
                </Group>

                <Group title="Try it">
                    <RN.View style={{ padding: 12 }}>
                        <RN.TextInput
                            value={text}
                            onChangeText={setText}
                            multiline
                            placeholder="Type something..."
                            placeholderTextColor="#80848e"
                            style={{
                                borderWidth: 1,
                                borderColor: "#4e5058",
                                borderRadius: 8,
                                padding: 10,
                                color: "#ffffff",
                            }}
                        />
                        <RN.Text style={{ color: "#dbdee1", marginTop: 10, fontSize: 15 }}>
                            {translate(text, { verbs: !!storage.verbs, slang: !!storage.slang })}
                        </RN.Text>
                    </RN.View>
                </Group>
            </Wrap>
        </RN.ScrollView>
    );
}
