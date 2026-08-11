/*
 * Copyright (c) 2005, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.script.rhino

import android.os.Build
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Member
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.Collections

/**
 * This class prevents script access to certain sensitive classes.
 * Note that this class checks over and above SecurityManager. i.e., although
 * a SecurityManager would pass, class shutter may still prevent access.
 *
 * @author A. Sundararajan
 * @since 1.6
 */
object RhinoClassShutter : ClassShutter {

    private val protectedClassNamesMatcher by lazy {
        ClassNameMatcher(emptyList())
    }

    private val systemClassProtectedName by lazy {
        emptySet<String>()
    }

    private val protectedClasses by lazy {
        emptyArray<Class<*>>()
    }

    fun visibleToScripts(obj: Any): Boolean {
        return visibleToScripts(obj.javaClass.name)
    }

    fun visibleToScripts(clazz: Class<*>): Boolean {
        protectedClasses.forEach {
            if (it.isAssignableFrom(clazz)) {
                return false
            }
        }
        return true
    }

    fun wrapJavaClass(scope: Scriptable, javaClass: Class<*>): Scriptable {
        return ProtectedNativeJavaClass(scope, javaClass)
    }

    override fun visibleToScripts(fullClassName: String): Boolean {
        return !protectedClassNamesMatcher.match(fullClassName)
    }

}