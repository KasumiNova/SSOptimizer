#include "github_kasuminova_ssoptimizer_common_render_engine_EngineBatchNative.h"
#include "ssoptimizer_render_common.h"

#include <algorithm>
#include <cstring>
#include <vector>

/**
 * 引擎合批 native flush：单次 JNI 完成整艘船的引擎绘制。
 *
 * 输入布局（与 EngineInstanceCollector 的扁平化常量一一对应）：
 * - 命令表：commandCount 条 16 字节记录（stage / textureId / instanceCount / dataOffset）
 * - 实例数据：条带 64B、核心 32B、辉光 44B 定长数组
 *
 * 顶点展开公式逐行对应 EngineInstanceCollector.expand*Vertices（Java 回退路径），
 * 顶点格式 20 字节（x,y,u,v float + rgba ubyte），索引为 uint16 GL_TRIANGLES。
 *
 * VBO 写入：环形偏移管理 + glBufferSubData（与 Java 路径同一驱动入口；
 * 老驱动兼容上下文上 glMapBufferRange(UNSYNCHRONIZED) 会产出扭曲顶点，
 * 已实测回归，勿再引入）；回绕时 glBufferData(NULL) 孤儿化旧存储。
 * 容量预检在 Java 侧完成，此处不扩容。
 *
 * VBO 绑定全程在本函数内恢复为进入时的值，不经 LWJGL：
 * LWJGL2 的 Buffer 校验基于 StateTracker 跟踪值，native 绑定不更新 tracker，
 * 只要恢复真实绑定，两者即保持一致。
 */

using namespace ssoptimizer::render;

namespace {

constexpr int32_t STAGE_STRIP = 0;
constexpr int32_t STAGE_CORE  = 1;
constexpr int32_t STAGE_GLOW  = 2;

constexpr size_t VERTEX_BYTES = 20;

struct EngineDrawCommand {
    int32_t stage;
    int32_t textureId;
    int32_t instanceCount;
    int32_t dataOffset;
};

struct StripInstanceData {
    float posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY;
    float halfWidth, innerLength, stripLength, texU, texSpan, texAdvance;
    uint8_t red, green, blue, alphaStart, alphaMid;
    uint8_t pad[3];
};
static_assert(sizeof(StripInstanceData) == 64);

struct CoreInstanceData {
    float posX, posY, angle, coreRotation, omegaRotation, stripLength, halfWidth;
    uint8_t red, green, blue, alpha;
};
static_assert(sizeof(CoreInstanceData) == 32);

struct GlowInstanceData {
    float posX, posY, angle, coreRotation, scaleX, size, texU0, texV0, texU1, texV1;
    uint8_t red, green, blue, alpha;
};
static_assert(sizeof(GlowInstanceData) == 44);

inline void rotatePoint(float& x, float& y, const float angleDeg) {
    float sinA;
    float cosA;
    computeSinCos(angleDeg, sinA, cosA);
    const float rx = x * cosA - y * sinA;
    const float ry = x * sinA + y * cosA;
    x = rx;
    y = ry;
}

inline void putVertex(uint8_t*& out, const float x, const float y,
                      const float u, const float v,
                      const uint8_t r, const uint8_t g, const uint8_t b, const uint8_t a) {
    std::memcpy(out, &x, sizeof(float));
    std::memcpy(out + 4, &y, sizeof(float));
    std::memcpy(out + 8, &u, sizeof(float));
    std::memcpy(out + 12, &v, sizeof(float));
    out[16] = r;
    out[17] = g;
    out[18] = b;
    out[19] = a;
    out += VERTEX_BYTES;
}

/** 条带实例展开为 6 顶点：T(pos)·R(angle)·R(rotation1)·R(rotation2)·T(translateX)·S(scaleX,scaleY)。 */
void expandStrip(const StripInstanceData& in, uint8_t*& out) {
    for (int vi = 0; vi < 6; vi++) {
        const float px = vi < 2 ? 0.0f : (vi < 4 ? in.innerLength : in.stripLength);
        const float py = (vi & 1) == 0 ? -in.halfWidth : in.halfWidth;
        const float u = vi < 2 ? in.texU : (vi < 4 ? in.texU + in.texSpan : in.texU + in.texAdvance);
        const float v = (vi & 1) == 0 ? TEX_MIN : TEX_MAX;
        const uint8_t alpha = static_cast<uint8_t>(vi < 2 ? in.alphaStart : (vi < 4 ? in.alphaMid : 0));

        float x = px * in.scaleX + in.translateX;
        float y = py * in.scaleY;
        rotatePoint(x, y, in.rotation2);
        rotatePoint(x, y, in.rotation1);
        rotatePoint(x, y, in.angle);

        putVertex(out, in.posX + x, in.posY + y, u, v, in.red, in.green, in.blue, alpha);
    }
}

/** 核心实例展开为 4 顶点：T(pos)·R(angle)·R(coreRotation)·S(0.9,1)·R(omegaRotation)。 */
void expandCore(const CoreInstanceData& in, uint8_t*& out) {
    for (int vi = 0; vi < 4; vi++) {
        const float px = vi < 2 ? 0.0f : in.stripLength;
        const float py = (vi & 1) == 0 ? -in.halfWidth : in.halfWidth;
        const float u = vi < 2 ? TEX_PAD : 1.0f - TEX_PAD;
        const float v = (vi & 1) == 0 ? TEX_MIN : TEX_MAX;

        float x = px;
        float y = py;
        rotatePoint(x, y, in.omegaRotation);
        x *= 0.9f;
        rotatePoint(x, y, in.coreRotation);
        rotatePoint(x, y, in.angle);

        putVertex(out, in.posX + x, in.posY + y, u, v, in.red, in.green, in.blue, in.alpha);
    }
}

/** 辉光实例展开为 4 顶点：T(pos)·R(angle)·R(coreRotation)·S(scaleX,1)·T(-S/2,-S/2)。 */
void expandGlow(const GlowInstanceData& in, uint8_t*& out) {
    const float half = in.size * 0.5f;
    for (int vi = 0; vi < 4; vi++) {
        const float cx = vi == 2 || vi == 3 ? in.size : 0.0f;
        const float cy = vi == 1 || vi == 2 ? in.size : 0.0f;
        const float u = vi == 2 || vi == 3 ? in.texU1 : in.texU0;
        const float v = vi == 1 || vi == 2 ? in.texV1 : in.texV0;

        float x = (cx - half) * in.scaleX;
        float y = cy - half;
        rotatePoint(x, y, in.coreRotation);
        rotatePoint(x, y, in.angle);

        putVertex(out, in.posX + x, in.posY + y, u, v, in.red, in.green, in.blue, in.alpha);
    }
}

inline void putIndex(uint8_t*& out, const uint16_t index) {
    std::memcpy(out, &index, sizeof(uint16_t));
    out += sizeof(uint16_t);
}

void appendStripIndices(uint8_t*& out, const uint16_t base) {
    // quad0: (0,1,3,2)，quad1: (2,3,5,4)
    putIndex(out, base);
    putIndex(out, base + 1);
    putIndex(out, base + 3);
    putIndex(out, base);
    putIndex(out, base + 3);
    putIndex(out, base + 2);
    putIndex(out, base + 2);
    putIndex(out, base + 3);
    putIndex(out, base + 5);
    putIndex(out, base + 2);
    putIndex(out, base + 5);
    putIndex(out, base + 4);
}

void appendCoreIndices(uint8_t*& out, const uint16_t base) {
    putIndex(out, base);
    putIndex(out, base + 1);
    putIndex(out, base + 3);
    putIndex(out, base);
    putIndex(out, base + 3);
    putIndex(out, base + 2);
}

void appendGlowIndices(uint8_t*& out, const uint16_t base) {
    putIndex(out, base);
    putIndex(out, base + 1);
    putIndex(out, base + 2);
    putIndex(out, base);
    putIndex(out, base + 2);
    putIndex(out, base + 3);
}

/** 环形写入空间保障：回绕时孤儿化旧存储并重置偏移。 */
void ensureRingSpace(const GLenum target, const GLuint vbo, const GLint capacity,
                     GLint& offset, const GLsizei bytes) {
    if (offset + bytes <= capacity) {
        return;
    }
    glBindBuffer(target, vbo);
    glBufferData(target, capacity, nullptr, GL_STREAM_DRAW);
    offset = 0;
}

thread_local std::vector<uint8_t> vertexScratch;
thread_local std::vector<uint8_t> indexScratch;

uint8_t* scratchSpace(std::vector<uint8_t>& scratch, const size_t bytes) {
    if (scratch.size() < bytes) {
        scratch.resize(bytes);
    }
    return scratch.data();
}

/** 展开数据写入环形区间（scratch + glBufferSubData，与 Java 路径同一驱动入口）。 */
void ringWrite(const GLenum target, const GLuint vbo, const GLint offset, const GLsizei bytes,
               const uint8_t* data) {
    glBindBuffer(target, vbo);
    glBufferSubData(target, offset, bytes, data);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_github_kasuminova_ssoptimizer_common_render_engine_EngineBatchNative_nativeFlushBatch(
        JNIEnv* env, jclass,
        jobject commandBuffer, jint commandCount,
        jint vertexVbo, jint vertexCapacity, jint vertexWriteOffset,
        jint indexVbo, jint indexCapacity, jint indexWriteOffset) {

    auto* commands = static_cast<const uint8_t*>(env->GetDirectBufferAddress(commandBuffer));
    if (commands == nullptr || commandCount <= 0) {
        return (static_cast<jlong>(vertexWriteOffset) << 32)
                | (static_cast<jlong>(indexWriteOffset) & 0xFFFFFFFFL);
    }

    GLint prevArrayBuffer = 0;
    GLint prevElementBuffer = 0;
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevArrayBuffer);
    glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING, &prevElementBuffer);

    glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
    glPushClientAttrib(GL_CLIENT_VERTEX_ARRAY_BIT);

    glEnable(GL_TEXTURE_2D);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE);
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glEnableClientState(GL_COLOR_ARRAY);

    GLint vertexOffset = vertexWriteOffset;
    GLint indexOffset = indexWriteOffset;

    for (jint ci = 0; ci < commandCount; ci++) {
        EngineDrawCommand cmd{};
        std::memcpy(&cmd, commands + static_cast<size_t>(ci) * sizeof(EngineDrawCommand),
                sizeof(EngineDrawCommand));
        if (cmd.instanceCount <= 0) {
            continue;
        }

        const int vertsPerInstance = cmd.stage == STAGE_STRIP ? 6 : 4;
        const int indicesPerInstance = cmd.stage == STAGE_STRIP ? 12 : 6;
        const int maxPerChunk = 65535 / vertsPerInstance;
        const uint8_t* instances = commands + cmd.dataOffset;

        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(cmd.textureId));

        int done = 0;
        while (done < cmd.instanceCount) {
            const int chunk = std::min(cmd.instanceCount - done, maxPerChunk);
            const auto vertexBytes = static_cast<GLsizei>(chunk * vertsPerInstance * VERTEX_BYTES);
            const auto indexBytes = static_cast<GLsizei>(chunk * indicesPerInstance * sizeof(uint16_t));

            ensureRingSpace(GL_ARRAY_BUFFER, static_cast<GLuint>(vertexVbo), vertexCapacity,
                    vertexOffset, vertexBytes);
            ensureRingSpace(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(indexVbo), indexCapacity,
                    indexOffset, indexBytes);

            // 顶点：scratch 展开后经 glBufferSubData 写入（与 Java 路径同一驱动入口）
            uint8_t* vout = scratchSpace(vertexScratch, vertexBytes);
            {
                uint8_t* out = vout;
                for (int i = 0; i < chunk; i++) {
                    const size_t instanceIndex = static_cast<size_t>(done + i);
                    switch (cmd.stage) {
                        case STAGE_STRIP: {
                            StripInstanceData in{};
                            std::memcpy(&in, instances + instanceIndex * sizeof(StripInstanceData),
                                    sizeof(StripInstanceData));
                            expandStrip(in, out);
                            break;
                        }
                        case STAGE_CORE: {
                            CoreInstanceData in{};
                            std::memcpy(&in, instances + instanceIndex * sizeof(CoreInstanceData),
                                    sizeof(CoreInstanceData));
                            expandCore(in, out);
                            break;
                        }
                        default: {
                            GlowInstanceData in{};
                            std::memcpy(&in, instances + instanceIndex * sizeof(GlowInstanceData),
                                    sizeof(GlowInstanceData));
                            expandGlow(in, out);
                            break;
                        }
                    }
                }
            }
            ringWrite(GL_ARRAY_BUFFER, static_cast<GLuint>(vertexVbo), vertexOffset, vertexBytes, vout);

            // 索引：chunk 内 baseVertex 从 0 重新开始（指针基址随 vertexOffset 移动）
            uint8_t* iout = scratchSpace(indexScratch, indexBytes);
            {
                uint8_t* out = iout;
                for (int i = 0; i < chunk; i++) {
                    const auto base = static_cast<uint16_t>(i * vertsPerInstance);
                    if (cmd.stage == STAGE_STRIP) {
                        appendStripIndices(out, base);
                    } else if (cmd.stage == STAGE_CORE) {
                        appendCoreIndices(out, base);
                    } else {
                        appendGlowIndices(out, base);
                    }
                }
            }
            ringWrite(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(indexVbo), indexOffset, indexBytes, iout);

            glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(vertexVbo));
            const auto base = static_cast<uintptr_t>(vertexOffset);
            glVertexPointer(2, GL_FLOAT, VERTEX_BYTES, reinterpret_cast<const void*>(base));
            glTexCoordPointer(2, GL_FLOAT, VERTEX_BYTES, reinterpret_cast<const void*>(base + 8));
            glColorPointer(4, GL_UNSIGNED_BYTE, VERTEX_BYTES, reinterpret_cast<const void*>(base + 16));
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(indexVbo));
            glDrawElements(GL_TRIANGLES, chunk * indicesPerInstance, GL_UNSIGNED_SHORT,
                    reinterpret_cast<const void*>(static_cast<uintptr_t>(indexOffset)));

            vertexOffset += vertexBytes;
            indexOffset += indexBytes;
            done += chunk;
        }
    }

    glPopClientAttrib();
    glPopAttrib();
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(prevElementBuffer));
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(prevArrayBuffer));

    return (static_cast<jlong>(vertexOffset) << 32)
            | (static_cast<jlong>(indexOffset) & 0xFFFFFFFFL);
}

} // extern "C"
