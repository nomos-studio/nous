// sc-headless.scd — sclang companion for nous SC bridge
// sclang boots scsynth and handles /cmd evaluation explicitly.

s.waitForBoot({
    // Explicit /cmd handler — evaluates SCLang code sent by nous send-synthdef!
    OSCdef(\nous_cmd, { |msg, time, addr|
        var code = msg[1].asString;
        ("=>[cmd] " ++ code.copyRange(0, 60)).postln;
        code.interpret;
    }, '/cmd');

    // Notify nous that the /cmd handler is active and scsynth is booted.
    // start-sc! registers a /sc-lang-ready handler before launching this script
    // and blocks on it — this message is the boot gate.
    NetAddr("127.0.0.1", 57121).sendMsg('/sc-lang-ready');

    "[sc-headless] scsynth booted, /cmd handler active — audio @ %:%".format(
        s.addr.hostname, s.addr.port
    ).postln;
});
