/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.koltinc

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringHash
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.jvm.javaClass

inline val scriptClassLoaderFactory get() : ScriptClassLoaderFactory = service()

@Service(Service.Level.APP)
class ScriptClassLoaderFactory {
    private fun orderedPluginDescriptors(): List<IdeaPluginDescriptor> {
        return PluginManagerCore.loadedPlugins
            .filter {
                //make sure we are not dealing with removed pluigns
                PluginManagerCore.isLoaded(it.pluginId)
            }
    }

    fun execCodeClassloader(jar: URL): ClassLoader {
        //we cannot keep the newIdeClassloader to enforce classes GC
        return URLClassLoader(arrayOf(jar), newIdeClassloader())
    }

    fun ideClasspath(): List<URL> {
        return orderedPluginDescriptors()
            .asSequence()
            .mapNotNull { it.pluginClassLoader }
            .distinct()
            .mapNotNull {
                try {
                    val method = it.javaClass.getMethod("getUrls")
                    method.trySetAccessible()
                    val data = method.invoke(it)
                    data as? List<*>
                } catch (e: Exception) {
                    null
                }
            }
            .flatten()
            .filterIsInstance<URL>()
            .distinct()
            .toList()
    }


    private fun newIdeClassloader(): ClassLoader {
        return object : ClassLoader(null) {
            val myLuckyGuess: ConcurrentMap<Long, ClassLoader> = ConcurrentHashMap()

            @Throws(ClassNotFoundException::class)
            override fun findClass(name: String): Class<*> {
                val hash = StringHash.buz(name.substringBefore("$"))

                var c: Class<*>? = null
                val guess1: ClassLoader? = myLuckyGuess[hash] // cached loader or "this" if not found
                val guess2: ClassLoader? = myLuckyGuess[0L]   // last recently used

                for (loader in setOf(guess1, guess2)) {
                    if (loader === this) throw ClassNotFoundException(name)
                    if (loader == null) continue

                    try {
                        return loader.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                        //nop
                    }
                }

                for (descriptor in orderedPluginDescriptors()) {
                    val l = descriptor.pluginClassLoader ?: continue
                    if (l === guess1 || l === guess2) continue

                    try {
                        c = l.loadClass(name)
                        myLuckyGuess[hash] = l
                        myLuckyGuess[0L] = l
                        break
                    } catch (_: ClassNotFoundException) {
                        //nop
                    }
                }

                if (c != null) {
                    return c
                } else {
                    myLuckyGuess[hash] = this
                    throw ClassNotFoundException(name)
                }
            }

            override fun findResource(name: String?): URL? {
                for (descriptor in orderedPluginDescriptors()) {
                    val l = descriptor.pluginClassLoader ?: continue
                    val url = l.getResource(name) ?: continue
                    return url
                }
                return null
            }

            @Throws(IOException::class)
            override fun findResources(name: String?): Enumeration<URL> {
                return sequence {
                    for (descriptor in orderedPluginDescriptors()) {
                        val l = descriptor.pluginClassLoader ?: continue
                        val urls = l.getResources(name) ?: continue
                        for (url in urls) {
                            yield(url ?: continue)
                        }
                    }
                }.toEnumeration()
            }
        }
    }

    private fun <T> Sequence<T>.toEnumeration(): Enumeration<T> {
        val iterator = this.iterator()
        return object : Enumeration<T> {
            override fun hasMoreElements(): Boolean = iterator.hasNext()
            override fun nextElement(): T? = iterator.next()
        }
    }
}
