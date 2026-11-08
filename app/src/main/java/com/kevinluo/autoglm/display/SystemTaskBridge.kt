package com.kevinluo.autoglm.display

import android.content.ComponentName
import android.os.Binder
import android.os.IBinder

/** Hidden Binder APIs confined to the shell process. Does not register a TaskOrganizer. */
internal class SystemTaskBridge {
    private val atmType = Class.forName("android.app.IActivityTaskManager")
    private val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    private val controllerType = Class.forName("android.window.IWindowOrganizerController")
    private val controller = atmType.getMethod("getWindowOrganizerController").invoke(atm)
    private val organizerType = Class.forName("android.window.ITaskOrganizerController")
    private val organizer = controllerType.getMethod("getTaskOrganizerController").invoke(controller)
    private val tokenType = Class.forName("android.window.WindowContainerToken")
    private val transactionType = Class.forName("android.window.WindowContainerTransaction")
    private val reparent = transactionType.getMethod("reparent", tokenType, tokenType, Boolean::class.javaPrimitiveType)
    private val reorder = transactionType.getMethod("reorder", tokenType, Boolean::class.javaPrimitiveType)
    private val deleteRoot = organizerType.getMethod("deleteRootTask", tokenType)
    private val apply = controllerType.getMethod("applyTransaction", transactionType)
    private val children = organizerType.getMethod("getChildTasks", tokenType, IntArray::class.java)
    private val parkingRoots = mutableListOf<Task>()

    data class Task(val id: Int, val displayId: Int, val userId: Int, val component: ComponentName?,
        val visible: Boolean, val token: Any, val raw: Any) {
        val packageName get() = component?.packageName.orEmpty()
    }
    fun tasks(displayId: Int): List<Task> {
        @Suppress("UNCHECKED_CAST")
        val records = organizerType.getMethod("getRootTasks", Int::class.javaPrimitiveType, IntArray::class.java)
            .invoke(organizer, displayId, null) as List<Any>
        return records.map(::record)
    }
    private fun record(raw: Any) = Task(field(raw, "taskId") as Int, field(raw, "displayId") as Int,
        field(raw, "userId") as Int, field(raw, "topActivity") as? ComponentName,
        field(raw, "isVisible") as Boolean, field(raw, "token")!!, raw)
    fun sameIdentity(before: Task, after: Task) = before.id == after.id && before.userId == after.userId && before.token == after.token

    fun find(displayId: Int, target: Task): Task? {
        val roots = tasks(displayId)
        roots.firstOrNull { sameIdentity(it, target) }?.let { return it }
        // getChildTasks is only permitted for roots created by the caller. Never traverse foreign containers.
        for (candidate in roots.filter { root -> parkingRoots.any { sameIdentity(root, it) } }) {
            @Suppress("UNCHECKED_CAST")
            val nested = children.invoke(organizer, candidate.token, null) as List<Any>
            nested.map(::record).firstOrNull { sameIdentity(it, target) }?.let { return it }
        }
        return null
    }

    fun settle(source: Task, onTop: Boolean): Task {
        if (tasks(source.displayId).any { sameIdentity(source, it) }) return source
        val current = find(source.displayId, source) ?: error("待恢复任务已失效")
        val transaction = transactionType.getConstructor().newInstance()
        reparent.invoke(transaction, current.token, null, onTop)
        apply.invoke(controller, transaction)
        val settled = tasks(source.displayId).singleOrNull { sameIdentity(source, it) }
            ?: error("任务层级恢复未完成")
        cleanEmptyParking()
        return settled
    }

    /** Reparent through an empty organizer root, flatten, then delete only the empty container. */
    fun move(source: Task, destination: Int, onTop: Boolean): Task {
        require(source.displayId != destination && destination >= 0)
        val current = find(source.displayId, source)
            ?: error("目标任务归属已变化，已停止迁移")
        val cookie = Binder()
        organizerType.getMethod("createRootTask", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IBinder::class.java)
            .invoke(organizer, destination, 1, cookie)
        val parking = tasks(destination).singleOrNull { candidate ->
            (field(candidate.raw, "launchCookies") as? List<*>)?.contains(cookie) == true
        } ?: error("无法识别临时任务容器")
        parkingRoots += parking
        try {
            val first = transactionType.getConstructor().newInstance()
            reorder.invoke(first, parking.token, false)
            reparent.invoke(first, current.token, parking.token, false)
            apply.invoke(controller, first)
            val second = transactionType.getConstructor().newInstance()
            reparent.invoke(second, current.token, null, onTop)
            apply.invoke(controller, second)
            val moved = tasks(destination).singleOrNull { sameIdentity(current, it) }
                ?: error("迁移未完成，保留原任务供恢复")
            check(empty(parking.token)) { "迁移容器仍有内容，保留供恢复" }
            check(deleteRoot.invoke(organizer, parking.token) as Boolean) { "空任务容器清理失败" }
            parkingRoots.remove(parking)
            return moved
        } finally {
            // A failed migration never deletes a container that still owns a user task.
            cleanEmptyParking()
        }
    }
    private fun cleanEmptyParking() {
        parkingRoots.toList().forEach { parked ->
            runCatching {
                if (empty(parked.token) && deleteRoot.invoke(organizer, parked.token) as Boolean) parkingRoots.remove(parked)
            }
        }
    }
    private fun empty(token: Any): Boolean = (children.invoke(organizer, token, null) as List<*>).isEmpty()
    private fun field(raw: Any, name: String): Any? = raw.javaClass.getField(name).get(raw)
}
