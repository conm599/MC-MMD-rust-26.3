package com.shiroha.mmdskin.render.backend.opengl;

import com.shiroha.mmdskin.render.backend.GlDisposal;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

/** 文件职责：释放并统计 CPU/OpenGL 蒙皮模型实例资源。 */
final class OpenGlModelLifecycle {
    private OpenGlModelLifecycle() {
    }

    static void dispose(OpenGlModelInstance target) {
        target.releaseBaseResources();

        if (target.posBuffer != null) {
            MemoryUtil.memFree(target.posBuffer);
            target.posBuffer = null;
        }
        if (target.colorBuffer != null) {
            MemoryUtil.memFree(target.colorBuffer);
            target.colorBuffer = null;
        }
        if (target.norBuffer != null) {
            MemoryUtil.memFree(target.norBuffer);
            target.norBuffer = null;
        }
        if (target.uv0Buffer != null) {
            MemoryUtil.memFree(target.uv0Buffer);
            target.uv0Buffer = null;
        }
        if (target.uv1Buffer != null) {
            MemoryUtil.memFree(target.uv1Buffer);
            target.uv1Buffer = null;
        }
        if (target.uv2Buffer != null) {
            MemoryUtil.memFree(target.uv2Buffer);
            target.uv2Buffer = null;
        }
        if (target.modelViewMatBuff != null) {
            MemoryUtil.memFree(target.modelViewMatBuff);
            target.modelViewMatBuff = null;
        }
        if (target.projMatBuff != null) {
            MemoryUtil.memFree(target.projMatBuff);
            target.projMatBuff = null;
        }
        if (target.light0Buff != null) {
            MemoryUtil.memFree(target.light0Buff);
            target.light0Buff = null;
        }
        if (target.light1Buff != null) {
            MemoryUtil.memFree(target.light1Buff);
            target.light1Buff = null;
        }
        if (target.subMeshDataBuf != null) {
            MemoryUtil.memFree(target.subMeshDataBuf);
            target.subMeshDataBuf = null;
        }

        // GL 对象销毁必须在渲染线程执行（OpenGL 上下文线程绑定，
        // 非渲染线程调用 glDelete* 会让 LWJGL 直接 abort 整个 JVM）。
        final int vertexArray = target.vertexArrayObject;
        final int[] buffers = {
                target.indexBufferObject,
                target.vertexBufferObject,
                target.colorBufferObject,
                target.normalBufferObject,
                target.texcoordBufferObject,
                target.uv1BufferObject,
                target.uv2BufferObject
        };
        final boolean ownsLightMapTexture = target.lightMapMaterial != null && target.lightMapMaterial.ownsTexture;
        final int lightMapTexture = ownsLightMapTexture ? target.lightMapMaterial.tex : 0;
        if (ownsLightMapTexture) {
            target.lightMapMaterial.tex = 0;
        }

        // 作废本地 id：销毁动作可能被排队执行（不在渲染线程时），
        // 立刻作废可以避免二次 dispose 把同一个名字再删一遍。
        target.vertexArrayObject = 0;
        target.indexBufferObject = 0;
        target.vertexBufferObject = 0;
        target.colorBufferObject = 0;
        target.normalBufferObject = 0;
        target.texcoordBufferObject = 0;
        target.uv1BufferObject = 0;
        target.uv2BufferObject = 0;

        GlDisposal.onRenderThread(() -> {
            GL46C.glDeleteVertexArrays(vertexArray);
            for (int buffer : buffers) {
                if (buffer > 0) {
                    GL46C.glDeleteBuffers(buffer);
                }
            }
            if (lightMapTexture > 0) {
                GL46C.glDeleteTextures(lightMapTexture);
            }
        });
    }

    static long getVramUsage(OpenGlModelInstance target) {
        long total = 0;
        int indexCount = (int) target.nativeBackendPort().getIndexCount(target.nativeModelHandle());
        total += (long) indexCount * target.indexElementSize;
        total += (long) target.vertexCount * 12 * 2;
        total += (long) target.vertexCount * 16;
        total += (long) target.vertexCount * 8 * 3;
        return total;
    }

    static long getRamUsage(OpenGlModelInstance target) {
        if (target.nativeModelHandle() == 0) {
            return 0;
        }

        long rustRam = target.nativeBackendPort().getModelMemoryUsage(target.nativeModelHandle());
        long javaRam = (long) target.vertexCount * 64;
        javaRam += 152;
        if (target.materialMorphResultCountValue() > 0) {
            javaRam += (long) target.materialMorphResultCountValue() * 56 * 4 * 2;
        }
        return rustRam + javaRam;
    }
}
