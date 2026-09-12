package com.sillytavern.app;

/** Launcher-alias target that opens instance 3 directly. */
public class Inst3Activity extends InstanceActivity {
    @Override
    protected int fixedInstanceId() {
        return 3;
    }
}
