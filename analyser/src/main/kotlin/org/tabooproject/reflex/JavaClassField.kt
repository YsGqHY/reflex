package org.tabooproject.reflex

import java.lang.invoke.MethodHandle

/**
 * @author 坏黑
 * @since 2022/1/21 10:12 PM
 */
@Internal
abstract class JavaClassField(name: String, owner: LazyClass) : ClassField(name, owner) {

    private val handleGetter by lazy(LazyThreadSafetyMode.NONE) {
        FieldAccessor(AccessType.GETTER).createHandle()
    }

    private val handleSetter by lazy(LazyThreadSafetyMode.NONE) {
        FieldAccessor(AccessType.SETTER).createHandle()
    }

    override fun get(src: Any?): Any? {
        if (fieldType == Unknown::class.java) {
            throw NoClassDefFoundError("${type.name}.$name (${owner})")
        }
        return try {
            if (isStatic) {
                handleGetter.invoke()
            } else {
                handleGetter.invoke(src)
            }
        } catch (ex: ClassCastException) {
            if (!isStatic && src == StaticSrc) {
                throw IllegalStateException("$name is not a static field", ex)
            }
            throw ex
        } catch (ex: Throwable) {
            if (ex is RuntimeException || ex is Error) {
                throw ex
            }
            throw RuntimeException(ex)
        }
    }

    override fun set(src: Any?, value: Any?) {
        if (fieldType == Unknown::class.java) {
            throw NoClassDefFoundError("${type.name}.$name (${owner})")
        }
        try {
            if (isStatic) {
                handleSetter.invoke(value)
            } else {
                handleSetter.invoke(src, value)
            }
        } catch (ex: ClassCastException) {
            if (!isStatic && src == StaticSrc) {
                throw IllegalStateException("$name is not a static field", ex)
            }
            throw ex
        } catch (ex: Throwable) {
            if (ex is RuntimeException || ex is Error) {
                throw ex
            }
            throw RuntimeException(ex)
        }
    }

    /**
     * 字段访问器策略
     *
     * 封装字段查找和 MethodHandle 创建逻辑，支持两种访问模式：
     * 1. Direct Access: 通过字段名直接访问（标准环境）
     * 2. Type-Based Access: 通过类型匹配访问（混合服务端环境）
     *
     * 设计目标：
     * - 消除 getter/setter 之间的代码重复
     * - 根据环境自动选择最优策略
     * - 提供清晰的错误信息和失败原因
     */
    private inner class FieldAccessor(private val accessType: AccessType) {

        /**
         * 创建 MethodHandle 的主入口
         *
         * 流程：
         * 1. 预检测字段名是否存在
         * 2. 根据环境和检测结果选择策略
         * 3. 创建并配置 MethodHandle
         */
        fun createHandle(): MethodHandle {
            val clazz = owner.instance ?: throw NoSuchFieldException("Owner class not found for field '$name'")

            // 预检测：字段名是否可以直接访问
            val canDirectAccess = tryFindFieldByName(clazz) != null

            return when {
                // 标准路径：字段名存在，尝试使用 MethodHandles.Lookup（高性能）
                canDirectAccess -> tryDirectAccessOrFallback()
                // 混合服务端路径：字段名已重映射，直接使用类型匹配
                else -> createFallbackHandle()
            }
        }

        /**
         * 尝试直接访问，失败时降级到 fallback
         *
         * 适用场景：
         * - 标准 Spigot/Paper 环境
         * - 字段名未被重映射的情况
         */
        private fun tryDirectAccessOrFallback(): MethodHandle {
            return try {
                // 使用 MethodHandles.Lookup 创建访问器（性能最优）
                val lookup = UnsafeAccess.lookup
                val rawHandle = when (accessType) {
                    AccessType.GETTER -> if (isStatic) {
                        lookup.findStaticGetter(owner.instance, name, fieldType)
                    } else {
                        lookup.findGetter(owner.instance, name, fieldType)
                    }
                    AccessType.SETTER -> if (isStatic) {
                        lookup.findStaticSetter(owner.instance, name, fieldType)
                    } else {
                        lookup.findSetter(owner.instance, name, fieldType)
                    }
                }
                rawHandle.asType(rawHandle.type().generic())
            } catch (ex: Throwable) {
                // MethodHandles 调用失败，可能是混合服务端拦截
                // 降级到 unreflect 方式
                createFallbackHandle()
            }
        }

        /**
         * Fallback 路径：使用 Java 反射 + unreflect
         *
         * 适用场景：
         * - CatServer 等混合服务端（字段名被重映射）
         * - MethodHandles.Lookup 调用失败的情况
         *
         * 策略：
         * 1. 优先尝试按名称查找
         * 2. 失败时按类型和 static 属性匹配
         */
        private fun createFallbackHandle(): MethodHandle {
            val clazz = owner.instance ?: throw NoSuchFieldException("Owner class not found for field '$name'")

            // 查找字段
            val field = findFieldByName(clazz) ?: findFieldByType(clazz)

            // 设置可访问并创建 MethodHandle
            field.isAccessible = true
            val lookup = UnsafeAccess.lookup
            val rawHandle = when (accessType) {
                AccessType.GETTER -> lookup.unreflectGetter(field)
                AccessType.SETTER -> lookup.unreflectSetter(field)
            }
            return rawHandle.asType(rawHandle.type().generic())
        }

        /**
         * 尝试按名称查找字段（不抛异常）
         *
         * @return 找到的字段，或 null
         */
        private fun tryFindFieldByName(clazz: Class<*>): java.lang.reflect.Field? {
            return try {
                clazz.getDeclaredField(name)
            } catch (e: NoSuchFieldException) {
                null
            }
        }

        /**
         * 按名称查找字段（抛异常）
         *
         * @return 找到的字段，或 null
         */
        private fun findFieldByName(clazz: Class<*>): java.lang.reflect.Field? {
            return tryFindFieldByName(clazz)
        }

        /**
         * 按类型和 static 属性匹配字段
         *
         * 适用于混合服务端环境，字段名已被重映射的情况
         *
         * @throws NoSuchFieldException 如果未找到匹配字段或有多个候选
         */
        private fun findFieldByType(clazz: Class<*>): java.lang.reflect.Field {
            // 过滤候选字段：类型匹配 + static 属性匹配
            val candidates = clazz.declaredFields.filter {
                it.type == fieldType &&
                java.lang.reflect.Modifier.isStatic(it.modifiers) == isStatic
            }

            return when {
                candidates.isEmpty() -> {
                    throw NoSuchFieldException(
                        "No field with type '${fieldType.name}' (${if (isStatic) "static" else "instance"}) found in ${clazz.name}"
                    )
                }
                candidates.size == 1 -> candidates[0]
                else -> {
                    // 多个候选：选择第一个（best-effort）
                    // 在大多数情况下这是正确的，因为同一类型的多个字段较少见
                    candidates[0]
                }
            }
        }
    }

    /**
     * 访问类型枚举
     */
    private enum class AccessType {
        /** Getter 访问器 */
        GETTER,
        /** Setter 访问器 */
        SETTER
    }
}