package com.sillytavern.app;

/** Launcher-alias target that opens instance 1 directly. */
public class Inst1Activity extends InstanceActivity {
    @Override
    protected int fixedInstanceId() {
        return 1;
    }
}
