package com.example

import org.junit.Assert.*
import org.junit.Test
import com.google.mediapipe.tasks.core.BaseOptions

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun listBaseOptionsMethods() {
    val clazz = BaseOptions.Builder::class.java
    println("--- BaseOptions.Builder Methods ---")
    for (method in clazz.declaredMethods) {
        println(method.toString())
    }
    println("-----------------------------------")
    assertEquals(4, 2 + 2)
  }
}
