package tk.zwander.common.tools

/**
 * Real-time streaming firmware porter that modifies firmware data during download
 * Performs byte-level modifications to port firmware from one device model to another
 */
class StreamingFirmwarePorter(
    private val sourceModel: String,
    private val targetModel: String
) {
    private val sourceBytes = sourceModel.toByteArray()
    private val targetBytes = targetModel.toByteArray()
    private val paddedTargetBytes = createPaddedTargetBytes()
    
    // Buffer to handle model name replacements that span across chunks
    private var carryOverBuffer = ByteArray(0)
    
    // Track position in firmware for context-aware porting
    private var totalBytesProcessed = 0L
    private var isInZipHeader = false
    private var isInTarHeader = false
    private var isInSystemPartition = false
    
    /**
     * Port a chunk of firmware data in real-time
     */
    fun portChunk(chunk: ByteArray, length: Int): ByteArray {
        val actualChunk = chunk.sliceArray(0 until length)
        val combinedData = carryOverBuffer + actualChunk
        
        // Analyze chunk context
        analyzeChunkContext(combinedData)
        
        // Perform porting based on context
        val portedData = when {
            isInZipHeader -> portZipHeader(combinedData)
            isInTarHeader -> portTarHeader(combinedData)
            isInSystemPartition -> portSystemPartition(combinedData)
            else -> portGenericData(combinedData)
        }
        
        // Handle potential model name split across chunks
        val (outputData, newCarryOver) = handleChunkBoundary(portedData)
        carryOverBuffer = newCarryOver
        
        totalBytesProcessed += length
        
        return outputData
    }
    
    private fun createPaddedTargetBytes(): ByteArray {
        return if (targetBytes.size < sourceBytes.size) {
            // Pad with null bytes if target is shorter
            targetBytes + ByteArray(sourceBytes.size - targetBytes.size)
        } else if (targetBytes.size > sourceBytes.size) {
            // Truncate if target is longer
            targetBytes.sliceArray(0 until sourceBytes.size)
        } else {
            targetBytes
        }
    }
    
    private fun analyzeChunkContext(data: ByteArray) {
        // Detect ZIP file headers (PK signature)
        isInZipHeader = data.size >= 4 && 
            data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()
        
        // Detect TAR file headers (ustar signature at offset 257)
        isInTarHeader = data.size > 262 && 
            data.sliceArray(257..261).contentEquals("ustar".toByteArray())
        
        // Detect system partition (common Android paths)
        val dataString = String(data, Charsets.ISO_8859_1)
        isInSystemPartition = dataString.contains("/system/") || 
            dataString.contains("/vendor/") || 
            dataString.contains("build.prop") ||
            dataString.contains("default.prop")
    }
    
    private fun portZipHeader(data: ByteArray): ByteArray {
        // Port ZIP file entries and central directory
        var result = data
        
        // Replace model names in file names within ZIP entries
        result = replaceBytes(result, sourceBytes, paddedTargetBytes)
        
        // Port specific ZIP entry names
        result = portZipEntryNames(result)
        
        return result
    }
    
    private fun portTarHeader(data: ByteArray): ByteArray {
        // Port TAR file headers and file names
        var result = data
        
        // Replace model names in TAR file names
        result = replaceBytes(result, sourceBytes, paddedTargetBytes)
        
        // Port TAR header fields
        result = portTarHeaderFields(result)
        
        return result
    }
    
    private fun portSystemPartition(data: ByteArray): ByteArray {
        // Port Android system partition files
        var result = data
        
        // Replace model names
        result = replaceBytes(result, sourceBytes, paddedTargetBytes)
        
        // Port build.prop entries
        result = portBuildPropEntries(result)
        
        // Port device-specific paths
        result = portDevicePaths(result)
        
        return result
    }
    
    private fun portGenericData(data: ByteArray): ByteArray {
        // Generic porting for any firmware data
        return replaceBytes(data, sourceBytes, paddedTargetBytes)
    }
    
    private fun portZipEntryNames(data: ByteArray): ByteArray {
        var result = data
        
        // Common Samsung firmware file patterns
        val patterns = mapOf(
            "${sourceModel.lowercase()}_" to "${targetModel.lowercase()}_",
            "${sourceModel.uppercase()}_" to "${targetModel.uppercase()}_",
            "/${sourceModel}/" to "/${targetModel}/",
            "-${sourceModel}-" to "-${targetModel}-"
        )
        
        patterns.forEach { (search, replace) ->
            result = replaceBytes(result, search.toByteArray(), replace.toByteArray())
        }
        
        return result
    }
    
    private fun portTarHeaderFields(data: ByteArray): ByteArray {
        var result = data
        
        // Port TAR header name field (first 100 bytes)
        if (data.size >= 100) {
            val nameField = data.sliceArray(0..99)
            val portedNameField = replaceBytes(nameField, sourceBytes, paddedTargetBytes)
            System.arraycopy(portedNameField, 0, result, 0, 100)
        }
        
        return result
    }
    
    private fun portBuildPropEntries(data: ByteArray): ByteArray {
        var result = data
        
        // Port common build.prop entries
        val propPatterns = mapOf(
            "ro.product.model=${sourceModel}" to "ro.product.model=${targetModel}",
            "ro.product.device=${sourceModel.lowercase()}" to "ro.product.device=${targetModel.lowercase()}",
            "ro.product.name=${sourceModel.lowercase()}" to "ro.product.name=${targetModel.lowercase()}",
            "ro.build.product=${sourceModel.lowercase()}" to "ro.build.product=${targetModel.lowercase()}"
        )
        
        propPatterns.forEach { (search, replace) ->
            result = replaceBytes(result, search.toByteArray(), replace.toByteArray())
        }
        
        return result
    }
    
    private fun portDevicePaths(data: ByteArray): ByteArray {
        var result = data
        
        // Port device-specific paths
        val pathPatterns = mapOf(
            "/dev/${sourceModel.lowercase()}" to "/dev/${targetModel.lowercase()}",
            "/sys/devices/${sourceModel.lowercase()}" to "/sys/devices/${targetModel.lowercase()}",
            "/proc/${sourceModel.lowercase()}" to "/proc/${targetModel.lowercase()}"
        )
        
        pathPatterns.forEach { (search, replace) ->
            result = replaceBytes(result, search.toByteArray(), replace.toByteArray())
        }
        
        return result
    }
    
    private fun handleChunkBoundary(data: ByteArray): Pair<ByteArray, ByteArray> {
        // Keep last few bytes as carry-over to handle model names split across chunks
        val carryOverSize = minOf(sourceBytes.size - 1, data.size)
        
        if (data.size <= carryOverSize) {
            return Pair(ByteArray(0), data)
        }
        
        val outputSize = data.size - carryOverSize
        val outputData = data.sliceArray(0 until outputSize)
        val newCarryOver = data.sliceArray(outputSize until data.size)
        
        return Pair(outputData, newCarryOver)
    }
    
    private fun replaceBytes(data: ByteArray, search: ByteArray, replace: ByteArray): ByteArray {
        if (search.isEmpty() || data.size < search.size) return data
        
        val result = data.toMutableList()
        var i = 0
        
        while (i <= result.size - search.size) {
            var match = true
            for (j in search.indices) {
                if (result[i + j] != search[j]) {
                    match = false
                    break
                }
            }
            
            if (match) {
                // Replace the matched bytes
                for (j in replace.indices) {
                    if (i + j < result.size) {
                        result[i + j] = replace[j]
                    }
                }
                i += search.size
            } else {
                i++
            }
        }
        
        return result.toByteArray()
    }
    
    /**
     * Finalize porting and return any remaining carry-over data
     */
    fun finalize(): ByteArray {
        val finalData = portGenericData(carryOverBuffer)
        carryOverBuffer = ByteArray(0)
        return finalData
    }
    
    /**
     * Get porting statistics
     */
    fun getStats(): PortingStats {
        return PortingStats(
            totalBytesProcessed = totalBytesProcessed,
            sourceModel = sourceModel,
            targetModel = targetModel
        )
    }
}

data class PortingStats(
    val totalBytesProcessed: Long,
    val sourceModel: String,
    val targetModel: String
)
