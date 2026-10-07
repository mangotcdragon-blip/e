for (var k = 0; k < 3; k += 1)
{
    if (btn_sent[k])
    {
        keyboard_key_release(btn_key[k]);
        btn_sent[k] = 0;
    }
}
