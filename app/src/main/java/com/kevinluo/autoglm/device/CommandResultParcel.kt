package com.kevinluo.autoglm.device

import android.os.Parcel
import android.os.Parcelable
import com.kevinluo.autoglm.core.CommandResult

/** Binder payload only. Business code consumes the immutable core result. */
class CommandResultParcel(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        if (parcel.readInt() == 1) parcel.readInt() else null,
        parcel.readString().orEmpty(), parcel.readString().orEmpty(),
        parcel.readInt() != 0, parcel.readInt() != 0,
    )

    fun toResult() = CommandResult(exitCode, stdout, stderr, timedOut, outputTruncated)

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(if (exitCode == null) 0 else 1)
        exitCode?.let(parcel::writeInt)
        parcel.writeString(stdout)
        parcel.writeString(stderr)
        parcel.writeInt(if (timedOut) 1 else 0)
        parcel.writeInt(if (outputTruncated) 1 else 0)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<CommandResultParcel> {
        override fun createFromParcel(parcel: Parcel) = CommandResultParcel(parcel)
        override fun newArray(size: Int): Array<CommandResultParcel?> = arrayOfNulls(size)
    }
}
