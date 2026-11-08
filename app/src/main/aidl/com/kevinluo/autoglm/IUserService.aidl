package com.kevinluo.autoglm;
import com.kevinluo.autoglm.device.CommandResultParcel;
import com.kevinluo.autoglm.device.DisplayResultParcel;

interface IUserService {
    void destroy() = 16777114;
    CommandResultParcel executeArgs(in String[] argv, long timeoutMs, int maxOutputBytes) = 3;
    DisplayResultParcel attachDisplay(String runId) = 4;
    DisplayResultParcel switchDisplay(String runId, long generation, boolean background) = 5;
    DisplayResultParcel inspectDisplay(String runId, long generation) = 6;
    DisplayResultParcel closeDisplay(String runId, long generation) = 7;
    DisplayResultParcel captureDisplay(String runId, long generation, String imageId) = 8;
    CommandResultParcel inputDisplay(String runId, long generation, int operation, in int[] values) = 9;
    CommandResultParcel launchOnDisplay(String runId, long generation, String component) = 10;
}
