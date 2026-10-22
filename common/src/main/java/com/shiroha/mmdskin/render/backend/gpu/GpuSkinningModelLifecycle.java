package com.shiroha.mmdskin.render.backend.gpu;

import com.shiroha.mmdskin.render.backend.GlDisposal;
import com.shiroha.mmdskin.render.shader.ShaderConstants;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

/** 文件职责：释放并统计 GPU skinning 模型实例资源。 */
final class GpuSkinningModelLifecycle {
    private GpuSkinningModelLifecycle() {
    }

    static long getVramUsage(GpuSkinningModelInstance target) {
        if (!target.initialized) {
            return 0;
        }

        long total = 0;
        int indexCount = (int) target.nativeBackendPort().getIndexCount(target.nativeModelHandle());
        total += (long) indexCount * target.indexElementSize;
        total += (long) target.vertexCount * 12 * 2;
        total += (long) target.vertexCount * 8;
        total += (long) target.vertexCount * 16 * 2;
        total += (long) target.vertexCount * 16;
        total += (long) target.vertexCount * 8 * 2;
        total += (long) target.vertexCount * 12 * 2;
        total += (long) ShaderConstants.MAX_BONES * 64;
        if (target.vertexMorphCount > 0) {
            total += target.nativeBackendPort().getGpuMorphOffsetsSize(target.nativeModelHandle());
            total += (long) target.vertexMorphCount * 4;
        }
        if (target.uvMorphCount > 0) {
            total += target.nativeBackendPort().getGpuUvMorphOffsetsSize(target.nativeModelHandle());
            total += (long) target.uvMorphCount * 4;
        }
        if (target.skinnedUvBuffer > 0) {
            total += (long) target.vertexCount * 8;
        }
        return total;
    }

    static long getRamUsage(GpuSkinningModelInstance target) {
        if (!target.initialized) {
            return 0;
        }

        long rustRam = target.nativeBackendPort().getModelMemoryUsage(target.nativeModelHandle());
        long javaRam = (long) target.vertexCount * 64;
        javaRam += 128;
        if (target.boneMatricesBuffer != null) {
            javaRam += (long) target.boneMatricesBuffer.capacity() * 4;
        }
        if (target.boneMatricesByteBuffer != null) {
            javaRam += target.boneMatricesByteBuffer.capacity();
        }
        if (target.morphWeightsBuffer != null) {
            javaRam += (long) target.morphWeightsBuffer.capacity() * 4;
        }
        if (target.morphWeightsByteBuffer != null) {
            javaRam += target.morphWeightsByteBuffer.capacity();
        }
        if (target.uvMorphWeightsBuffer != null) {
            javaRam += (long) target.uvMorphWeightsBuffer.capacity() * 4;
        }
        if (target.uvMorphWeightsByteBuffer != null) {
            javaRam += target.uvMorphWeightsByteBuffer.capacity();
        }
        if (target.materialMorphResultCountValue() > 0) {
            javaRam += (long) target.materialMorphResultCountValue() * 56 * 4 * 2;
        }
        if (target.subMeshDataBuf != null) {
            javaRam += target.subMeshDataBuf.capacity();
        }
        return rustRam + javaRam;
    }

    static void dispose(GpuSkinningModelInstance target) {
        if (!target.initialized) {
            return;
        }

        target.initialized = false;
        target.releaseBaseResources();

        // GL 对象销毁必须在渲染线程执行。
        // OpenGL 上下文是线程绑定的：在别的线程（断线时是 Netty IO 线程）调用 glDelete*，
        // LWJGL 会抛 "No context is current" 并直接 abort 整个 JVM —— 现象是
        // 「保存并退出到标题」瞬间闪退，没有 Java 异常栈，也没有崩溃报告。
        // 销毁动作交回渲染线程：已在渲染线程就就地做，否则排到下一次渲染任务。
        final int vertexArray = target.vertexArrayObject;
        final int[] buffers = {
                target.indexBufferObject,
                target.positionBufferObject,
                target.normalBufferObject,
                target.uv0BufferObject,
                target.boneIndicesBufferObject,
                target.boneWeightsBufferObject,
                target.colorBufferObject,
                target.uv1BufferObject,
                target.uv2BufferObject,
                target.skinnedPositionsBuffer,
                target.skinnedNormalsBuffer
        };
        final int[] shaderBuffers = {
                target.boneMatrixSSBO,
                target.morphOffsetsSSBO,
                target.morphWeightsSSBO,
                target.uvMorphOffsetsSSBO,
                target.uvMorphWeightsSSBO,
                target.skinnedUvBuffer
        };
        final boolean ownsLightMapTexture = target.lightMapMaterial != null && target.lightMapMaterial.ownsTexture;
        final int lightMapTexture = ownsLightMapTexture ? target.lightMapMaterial.tex : 0;
        if (ownsLightMapTexture) {
            target.lightMapMaterial.tex = 0;
        }

        GlDisposal.onRenderThread(() -> {
            GL46C.glDeleteVertexArrays(vertexArray);
            for (int buffer : buffers) {
                if (buffer > 0) {
                    GL46C.glDeleteBuffers(buffer);
                }
            }
            for (int shaderBuffer : shaderBuffers) {
                if (shaderBuffer > 0) {
                    GL46C.glDeleteBuffers(shaderBuffer);
                }
            }
            if (lightMapTexture > 0) {
                GL46C.glDeleteTextures(lightMapTexture);
            }
        });

        target.boneMatrixSSBO = 0;
        target.morphOffsetsSSBO = 0;
        target.morphWeightsSSBO = 0;
        target.uvMorphOffsetsSSBO = 0;
        target.uvMorphWeightsSSBO = 0;
        target.skinnedUvBuffer = 0;

        if (target.boneMatricesBuffer != null) {
            MemoryUtil.memFree(target.boneMatricesBuffer);
            target.boneMatricesBuffer = null;
        }
        if (target.boneMatricesByteBuffer != null) {
            MemoryUtil.memFree(target.boneMatricesByteBuffer);
            target.boneMatricesByteBuffer = null;
        }
        if (target.morphWeightsBuffer != null) {
            MemoryUtil.memFree(target.morphWeightsBuffer);
            target.morphWeightsBuffer = null;
        }
        target.morphWeightsByteBuffer = null;
        if (target.uvMorphWeightsBuffer != null) {
            MemoryUtil.memFree(target.uvMorphWeightsBuffer);
            target.uvMorphWeightsBuffer = null;
        }
        target.uvMorphWeightsByteBuffer = null;
        if (target.modelViewMatBuff != null) {
            MemoryUtil.memFree(target.modelViewMatBuff);
            target.modelViewMatBuff = null;
        }
        if (target.projMatBuff != null) {
            MemoryUtil.memFree(target.projMatBuff);
            target.projMatBuff = null;
        }
        if (target.subMeshDataBuf != null) {
            MemoryUtil.memFree(target.subMeshDataBuf);
            target.subMeshDataBuf = null;
        }
    }
}
