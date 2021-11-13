/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

@CacheableTask
abstract class InspectVanillaJar : BaseTask() {

    @get:Classpath
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val loggerFile: RegularFileProperty

    @get:OutputFile
    abstract val syntheticMethods: RegularFileProperty

    override fun init() {
        loggerFile.convention(defaultOutput("$name-loggerFields", "txt"))
        syntheticMethods.convention(defaultOutput("$name-syntheticMethods", "txt"))
    }

    @TaskAction
    fun run() {
        val loggers = mutableListOf<LoggerFields.Data>()
        val synthMethods = mutableListOf<SyntheticMethods.Data>()

        val loggerFieldsVisitor = LoggerFields.Visitor(null, loggers)

        inputJar.path.openZip().use { inJar ->
            val rootMatcher = inJar.getPathMatcher("glob:/*.class")
            val nmsMatcher = inJar.getPathMatcher("glob:/net/minecraft/**/*.class")

            inJar.walk().use { stream ->
                stream.filter { p -> !p.isDirectory() }
                    .filter { p -> rootMatcher.matches(p) || nmsMatcher.matches(p) }
                    .map { p -> ClassReader(p.readBytes()) }
                    .forEach { reader ->
                        reader.accept(loggerFieldsVisitor, 0)
                        val classNode = ClassNode()
                        reader.accept(classNode, 0)
                        SyntheticMethods.addSynths(synthMethods, classNode)
                    }
            }
        }

        loggerFile.path.bufferedWriter(Charsets.UTF_8).use { writer ->
            loggers.sort()
            for (loggerField in loggers) {
                writer.append(loggerField.className)
                writer.append(' ')
                writer.append(loggerField.fieldName)
                writer.newLine()
            }
        }

        syntheticMethods.path.bufferedWriter(Charsets.UTF_8).use { writer ->
            synthMethods.sort()
            for ((className, desc, synthName, baseName) in synthMethods) {
                writer.append(className)
                writer.append(' ')
                writer.append(desc)
                writer.append(' ')
                writer.append(synthName)
                writer.append(' ')
                writer.append(baseName)
                writer.newLine()
            }
        }
    }
}

abstract class BaseClassVisitor(classVisitor: ClassVisitor?) : ClassVisitor(Opcodes.ASM9, classVisitor), AsmUtil {
    protected var currentClass: String? = null

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.currentClass = name
        super.visit(version, access, name, signature, superName, interfaces)
    }
}

/*
 * SpecialSource2 automatically maps all Logger fields to the name LOGGER, without needing mappings defined, so we need
 * to make a note of all of those fields
 */
object LoggerFields {
    class Visitor(
        classVisitor: ClassVisitor?,
        private val fields: MutableList<Data>
    ) : BaseClassVisitor(classVisitor) {

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            val ret = super.visitField(access, name, descriptor, signature, value)
            val className = currentClass ?: return ret

            if (Opcodes.ACC_STATIC !in access || Opcodes.ACC_FINAL !in access) {
                return ret
            }
            if (descriptor != "Lorg/apache/logging/log4j/Logger;") {
                return ret
            }
            fields += Data(className, name)
            return ret
        }
    }

    data class Data(val className: String, val fieldName: String) : Comparable<Data> {
        override fun compareTo(other: Data) = compareValuesBy(
            this,
            other,
            { it.className },
            { it.fieldName }
        )
    }
}

/*
 * SpecialSource2 automatically handles certain synthetic method renames, which leads to methods which don't match any
 * existing mapping. We need to make a note of all of the synthetic methods which match SpecialSource2's checks so we
 * can handle it in our generated mappings.
 */
object SyntheticMethods : AsmUtil {
    fun addSynths(synths: MutableList<Data>, classNode: ClassNode) {
        for (methodNode in classNode.methods) {
            if (Opcodes.ACC_SYNTHETIC !in methodNode.access ||
                Opcodes.ACC_BRIDGE in methodNode.access ||
                Opcodes.ACC_PRIVATE in methodNode.access ||
                methodNode.name.contains('$')
            ) continue

            val (baseName, baseDesc) = SyntheticUtil.findBaseMethod(methodNode, classNode.name, classNode.methods)

            if (baseName != methodNode.name || baseDesc != methodNode.desc) {
                // Add this method as a synthetic for baseName
                synths += Data(classNode.name, baseDesc, methodNode.name, baseName)
            }
        }
    }

    data class Data(
        val className: String,
        val desc: String,
        val synthName: String,
        val baseName: String
    ) : Comparable<Data> {
        override fun compareTo(other: Data) = compareValuesBy(
            this,
            other,
            { it.className },
            { it.desc },
            { it.synthName },
            { it.baseName }
        )
    }
}
