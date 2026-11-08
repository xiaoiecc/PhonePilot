package com.kevinluo.autoglm.device

import android.os.Parcel
import android.os.Parcelable

/** Typed resource receipt. No executable command or system token crosses this boundary. */
class DisplayResultParcel(
    val success: Boolean, val message: String, val runId: String, val generation: Long,
    val displayId: Int, val taskId: Int, val packageName: String,
    val width: Int, val height: Int, val density: Int, val background: Boolean,
    val imagePath: String = "",
) : Parcelable {
    private constructor(p: Parcel) : this(p.readInt() != 0, p.readString().orEmpty(), p.readString().orEmpty(),
        p.readLong(), p.readInt(), p.readInt(), p.readString().orEmpty(), p.readInt(), p.readInt(), p.readInt(),
        p.readInt() != 0, p.readString().orEmpty())
    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeInt(if (success) 1 else 0); p.writeString(message); p.writeString(runId); p.writeLong(generation)
        p.writeInt(displayId); p.writeInt(taskId); p.writeString(packageName)
        p.writeInt(width); p.writeInt(height); p.writeInt(density); p.writeInt(if (background) 1 else 0); p.writeString(imagePath)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<DisplayResultParcel> {
        override fun createFromParcel(p: Parcel) = DisplayResultParcel(p)
        override fun newArray(size: Int): Array<DisplayResultParcel?> = arrayOfNulls(size)
    }
}
