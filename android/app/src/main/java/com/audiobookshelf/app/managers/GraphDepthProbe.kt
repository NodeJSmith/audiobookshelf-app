package com.audiobookshelf.app.managers

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Debug-only diagnostic: walks an object graph the way Kryo's field serializer does (every
 * non-static, non-transient field, superclasses included, plus collection/map/array elements) and
 * logs the field path when nesting passes [REPORT_DEPTH] or an object reappears on its own path.
 * Used to find which field makes Paper's DeviceData save recurse without end on some devices.
 */
object GraphDepthProbe {
  private const val TAG = "GraphProbe"
  private const val REPORT_DEPTH = 50
  private const val MAX_NODES = 200_000

  private class Walk {
    val path = ArrayList<String>()
    val onPath = IdentityHashMap<Any, Int>()
    var nodes = 0
    var reported = false
  }

  fun probe(label: String, root: Any?) {
    Thread {
      val walk = Walk()
      val start = System.currentTimeMillis()
      try {
        visit(root, walk)
      } catch (t: Throwable) {
        Log.e(TAG, "$label: probe failed at ${walk.path.joinToString(" -> ")}", t)
      }
      Log.i(TAG, "$label: done nodes=${walk.nodes} reported=${walk.reported} ms=${System.currentTimeMillis() - start}")
    }.start()
  }

  private fun isLeaf(obj: Any): Boolean {
    val c = obj.javaClass
    return c.isPrimitive || obj is String || obj is Number || obj is Boolean || obj is Char || c.isEnum ||
            (c.superclass?.isEnum == true)
  }

  private fun visit(obj: Any?, walk: Walk) {
    if (obj == null || walk.reported || walk.nodes > MAX_NODES || isLeaf(obj)) return
    walk.nodes++

    val seenAt = walk.onPath[obj]
    if (seenAt != null) {
      report("cycle: ${obj.javaClass.name} reappears; first at depth $seenAt", walk)
      return
    }
    if (walk.path.size >= REPORT_DEPTH) {
      report("depth ${walk.path.size} at ${obj.javaClass.name}", walk)
      return
    }

    walk.onPath[obj] = walk.path.size
    try {
      when (obj) {
        is Collection<*> -> obj.forEachIndexed { i, e -> step("${obj.javaClass.simpleName}[$i]", e, walk) }
        is Map<*, *> -> obj.entries.forEachIndexed { i, e -> step("${obj.javaClass.simpleName}{$i}", e.value, walk) }
        is Array<*> -> obj.forEachIndexed { i, e -> step("array[$i]", e, walk) }
        else -> fieldsOf(obj.javaClass).forEach { f ->
          val value = try { f.get(obj) } catch (t: Throwable) { null }
          step("${f.declaringClass.name.removePrefix("com.audiobookshelf.app.")}.${f.name}<${value?.javaClass?.name}>", value, walk)
        }
      }
    } finally {
      walk.onPath.remove(obj)
    }
  }

  private fun step(name: String, value: Any?, walk: Walk) {
    walk.path.add(name)
    try { visit(value, walk) } finally { walk.path.removeAt(walk.path.size - 1) }
  }

  private fun fieldsOf(cls: Class<*>): List<Field> {
    val out = ArrayList<Field>()
    var c: Class<*>? = cls
    while (c != null && c != Any::class.java) {
      for (f in c.declaredFields) {
        if (Modifier.isStatic(f.modifiers) || Modifier.isTransient(f.modifiers)) continue
        try { f.isAccessible = true } catch (t: Throwable) { continue }
        out.add(f)
      }
      c = c.superclass
    }
    return out
  }

  private fun report(reason: String, walk: Walk) {
    walk.reported = true
    Log.w(TAG, "REPORT $reason; path (${walk.path.size}):")
    walk.path.forEachIndexed { i, p -> Log.w(TAG, "  $i: $p") }
  }
}
