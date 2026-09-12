package com.sillytavern.app;

/** Launcher-alias target that opens instance 5 directly. */
public class Inst5Activity extends InstanceActivity {
    @Override
    protected int fixedInstanceId() {
        return 5;
    }
}
