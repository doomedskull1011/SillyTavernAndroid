package com.sillytavern.app;

/** Launcher-alias target that opens instance 2 directly. */
public class Inst2Activity extends InstanceActivity {
    @Override
    protected int fixedInstanceId() {
        return 2;
    }
}
