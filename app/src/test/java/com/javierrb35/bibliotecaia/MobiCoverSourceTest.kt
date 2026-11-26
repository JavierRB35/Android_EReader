package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.ui.MobiCoverSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class MobiCoverSourceTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun readsOnlyDeclaredCoverAndRejectsInvalidOffset() {
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 4, 0xff.toByte(), 0xd9.toByte())
        val header = ByteBuffer.allocate(156)
        header.position(16); header.put("MOBI".toByteArray()); header.putInt(116)
        header.putInt(108, 2)
        header.position(132); header.put("EXTH".toByteArray()); header.putInt(24); header.putInt(1)
        header.putInt(201); header.putInt(12); header.putInt(0)
        val start = 78 + 3 * 8
        val file = ByteBuffer.allocate(start + header.capacity() + 1 + image.size)
        file.position(60); file.put("BOOKMOBI".toByteArray())
        file.putShort(76, 3.toShort())
        file.putInt(78, start); file.putInt(86, start + header.capacity())
        file.putInt(94, start + header.capacity() + 1)
        file.position(start); file.put(header.array()); file.put(0.toByte()); file.put(image)
        val original = folder.newFile("book.mobi")
        original.writeBytes(file.array())
        assertArrayEquals(image, MobiCoverSource.read(original))
        file.putInt(start + 152, -1)
        original.writeBytes(file.array())
        assertNull(MobiCoverSource.read(original))
    }
}
