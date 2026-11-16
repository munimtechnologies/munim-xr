package com.margelo.nitro.munimxr

import android.opengl.GLES20
import android.opengl.Matrix
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Non-indexed triangles, interleaved as `x, y, z, nx, ny, nz` per vertex. */
internal class ObjMesh(val vertices: FloatArray) {
  val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX

  companion object {
    const val FLOATS_PER_VERTEX = 6
  }
}

/**
 * Wavefront OBJ geometry reader: `v`, `vn` and `f` (any of `v`, `v/vt`,
 * `v//vn`, `v/vt/vn`, negative indices, polygons triangulated as fans).
 * Faces without normals get a flat face normal. Materials and textures are
 * ignored; the renderer draws a neutral lit material.
 */
internal object ObjParser {
  private val WHITESPACE = Regex("\\s+")

  fun parse(input: InputStream): ObjMesh {
    val positions = FloatBuilder()
    val normals = FloatBuilder()
    val output = FloatBuilder()
    val corners = ArrayList<IntArray>(4)
    input.bufferedReader().useLines { lines ->
      for ((lineIndex, raw) in lines.withIndex()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val parts = line.split(WHITESPACE)
        when (parts[0]) {
          "v" -> {
            require(parts.size >= 4) { "OBJ line ${lineIndex + 1}: a vertex needs x, y and z." }
            positions.add(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
          }
          "vn" -> {
            require(parts.size >= 4) { "OBJ line ${lineIndex + 1}: a normal needs x, y and z." }
            normals.add(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
          }
          "f" -> {
            require(parts.size >= 4) { "OBJ line ${lineIndex + 1}: a face needs at least 3 vertices." }
            corners.clear()
            for (index in 1 until parts.size) {
              corners.add(corner(parts[index], positions.size / 3, normals.size / 3, lineIndex))
            }
            for (index in 1 until corners.size - 1) {
              triangle(positions, normals, output, corners[0], corners[index], corners[index + 1])
            }
          }
        }
      }
    }
    require(output.size > 0) { "The OBJ file contains no faces." }
    return ObjMesh(output.toArray())
  }

  /** Returns `[positionIndex, normalIndex or -1]`, both zero-based. */
  private fun corner(token: String, positionCount: Int, normalCount: Int, lineIndex: Int): IntArray {
    val fields = token.split('/')
    val position = resolve(fields[0], positionCount, lineIndex)
    val normal = if (fields.size >= 3 && fields[2].isNotEmpty()) {
      resolve(fields[2], normalCount, lineIndex)
    } else {
      -1
    }
    return intArrayOf(position, normal)
  }

  private fun resolve(field: String, count: Int, lineIndex: Int): Int {
    val value = field.toInt()
    val index = if (value < 0) count + value else value - 1
    require(index in 0 until count) { "OBJ line ${lineIndex + 1}: index $value is out of range." }
    return index
  }

  private fun triangle(
    positions: FloatBuilder,
    normals: FloatBuilder,
    output: FloatBuilder,
    a: IntArray,
    b: IntArray,
    c: IntArray,
  ) {
    val flat = if (a[1] < 0 || b[1] < 0 || c[1] < 0) faceNormal(positions, a[0], b[0], c[0]) else null
    for (corner in arrayOf(a, b, c)) {
      val p = corner[0] * 3
      output.add(positions[p], positions[p + 1], positions[p + 2])
      if (flat != null) {
        output.add(flat[0], flat[1], flat[2])
      } else {
        val n = corner[1] * 3
        output.add(normals[n], normals[n + 1], normals[n + 2])
      }
    }
  }

  private fun faceNormal(positions: FloatBuilder, a: Int, b: Int, c: Int): FloatArray {
    val ax = positions[a * 3]
    val ay = positions[a * 3 + 1]
    val az = positions[a * 3 + 2]
    val ux = positions[b * 3] - ax
    val uy = positions[b * 3 + 1] - ay
    val uz = positions[b * 3 + 2] - az
    val vx = positions[c * 3] - ax
    val vy = positions[c * 3 + 1] - ay
    val vz = positions[c * 3 + 2] - az
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val length = sqrt(nx * nx + ny * ny + nz * nz)
    return if (length > 1e-12f) floatArrayOf(nx / length, ny / length, nz / length) else floatArrayOf(0f, 1f, 0f)
  }

  /** Growable float array without boxing. */
  private class FloatBuilder {
    private var data = FloatArray(1024)
    var size = 0
      private set

    operator fun get(index: Int): Float = data[index]

    fun add(x: Float, y: Float, z: Float) {
      if (size + 3 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 3))
      data[size] = x
      data[size + 1] = y
      data[size + 2] = z
      size += 3
    }

    fun toArray(): FloatArray = data.copyOf(size)
  }
}

/** A model added with `addModel()`. GL-thread only. */
internal class XRModelEntry(
  val mesh: ObjMesh,
  val anchorId: String?,
  var localTransform: FloatArray,
  var scale: Float,
) {
  /** GL buffer name; 0 until uploaded (and again after an EGL context loss). */
  var vertexBuffer = 0
}

/**
 * Draws [XRModelEntry] meshes over the camera background with depth testing
 * and a fixed directional light. All methods run on the GL thread.
 */
internal class XRModelRenderer {
  private var program = 0
  private var positionAttribute = -1
  private var normalAttribute = -1
  private var mvpUniform = -1
  private var modelUniform = -1
  private val modelViewProjection = FloatArray(16)

  /** Call from `onSurfaceCreated`: a new EGL context invalidates every GL name. */
  fun onSurfaceCreated(entries: Collection<XRModelEntry>) {
    program = XRGL.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
    normalAttribute = GLES20.glGetAttribLocation(program, "a_Normal")
    mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
    modelUniform = GLES20.glGetUniformLocation(program, "u_Model")
    for (entry in entries) entry.vertexBuffer = 0
  }

  fun release(entry: XRModelEntry) {
    if (entry.vertexBuffer != 0) {
      GLES20.glDeleteBuffers(1, intArrayOf(entry.vertexBuffer), 0)
      entry.vertexBuffer = 0
    }
  }

  fun draw(entry: XRModelEntry, model: FloatArray, viewProjection: FloatArray) {
    if (program == 0) return
    if (entry.vertexBuffer == 0) upload(entry)
    Matrix.multiplyMM(modelViewProjection, 0, viewProjection, 0, model, 0)

    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    GLES20.glDepthMask(true)
    GLES20.glUseProgram(program)
    GLES20.glUniformMatrix4fv(mvpUniform, 1, false, modelViewProjection, 0)
    GLES20.glUniformMatrix4fv(modelUniform, 1, false, model, 0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, entry.vertexBuffer)
    val stride = ObjMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
    GLES20.glEnableVertexAttribArray(positionAttribute)
    GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, stride, 0)
    GLES20.glEnableVertexAttribArray(normalAttribute)
    GLES20.glVertexAttribPointer(normalAttribute, 3, GLES20.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, entry.mesh.vertexCount)
    GLES20.glDisableVertexAttribArray(positionAttribute)
    GLES20.glDisableVertexAttribArray(normalAttribute)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
  }

  private fun upload(entry: XRModelEntry) {
    val vertices = entry.mesh.vertices
    val data = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .put(vertices)
    data.position(0)
    val names = IntArray(1)
    GLES20.glGenBuffers(1, names, 0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, names[0])
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * Float.SIZE_BYTES, data, GLES20.GL_STATIC_DRAW)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    entry.vertexBuffer = names[0]
  }

  private companion object {
    const val VERTEX_SHADER = """
      uniform mat4 u_ModelViewProjection;
      uniform mat4 u_Model;
      attribute vec3 a_Position;
      attribute vec3 a_Normal;
      varying vec3 v_Normal;
      void main() {
        gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
        v_Normal = (u_Model * vec4(a_Normal, 0.0)).xyz;
      }
    """

    // Neutral grey, lit from above and slightly in front, with ambient fill.
    const val FRAGMENT_SHADER = """
      precision mediump float;
      varying vec3 v_Normal;
      void main() {
        vec3 light = normalize(vec3(0.3, 1.0, 0.5));
        float diffuse = max(dot(normalize(v_Normal), light), 0.0);
        vec3 base = vec3(0.82, 0.84, 0.86);
        gl_FragColor = vec4(base * (0.35 + 0.65 * diffuse), 1.0);
      }
    """
  }
}

internal object XRGL {
  fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "XR shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    return program
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "XR shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    return shader
  }
}
