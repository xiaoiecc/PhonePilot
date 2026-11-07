package com.kevinluo.autoglm;
import com.kevinluo.autoglm.device.CommandResultParcel;

interface IUserService {
    void destroy() = 16777114;
    CommandResultParcel executeArgs(in String[] argv, long timeoutMs, int maxOutputBytes) = 3;
}
