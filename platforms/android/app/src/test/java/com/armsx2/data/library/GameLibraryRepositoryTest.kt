package com.armsx2.data.library

import android.content.SharedPreferences
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameLibraryRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: GameLibraryRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        MainActivityRuntime.prefs = fakePrefs
        repository = GameLibraryRepository(context = null)
    }

    @Test
    fun `varredura com pasta inexistente falha na leitura e nao grava cache vazio`() = runBlocking {
        // Pré-condição: cache anterior válido com chave e json existentes
        val previousCacheJson = """[{"uri":"file:///storage/roms/God%20of%20War.iso","title":"God of War"}]"""
        val previousKey = "/storage/roms"
        fakePrefs.putString("gamesCacheKey", previousKey)
        fakePrefs.putString("gamesCache", previousCacheJson)

        val nonExistentDir = File(temp.root, "pasta_roms_desmontada").absolutePath
        val games = repository.scan(listOf(nonExistentDir))

        // O resultado da varredura na pasta inexistente é vazio
        assertTrue(games.isEmpty())
        // A flag lastScanAllRead sinaliza que nem todas as pastas foram lidas
        assertFalse(repository.lastScanAllRead)

        // O cache bom anterior NÃO pode ter sido sobrescrito com "[]"
        assertEquals(previousKey, fakePrefs.getString("gamesCacheKey", null))
        assertEquals(previousCacheJson, fakePrefs.getString("gamesCache", null))
    }

    @Test
    fun `varredura com caminho que nao e diretorio nao grava cache`() = runBlocking {
        val fileNotDir = temp.newFile("arquivo_comum.bin").absolutePath

        val previousCacheJson = """[{"uri":"file:///storage/roms/GT4.iso","title":"Gran Turismo 4"}]"""
        fakePrefs.putString("gamesCacheKey", "minha_chave")
        fakePrefs.putString("gamesCache", previousCacheJson)

        val games = repository.scan(listOf(fileNotDir))
        assertTrue(games.isEmpty())
        assertFalse(repository.lastScanAllRead)

        // Cache permanece o anterior
        assertEquals("minha_chave", fakePrefs.getString("gamesCacheKey", null))
        assertEquals(previousCacheJson, fakePrefs.getString("gamesCache", null))
    }

    @Test
    fun `varredura com pasta valida e legivel grava cache normalmente`() = runBlocking {
        val validEmptyDir = temp.newFolder("pasta_roms_vazia")

        fakePrefs.putString("gamesCacheKey", "chave_antiga")
        fakePrefs.putString("gamesCache", """[{"uri":"file:///old.iso","title":"Old"}]""")

        val games = repository.scan(listOf(validEmptyDir.absolutePath))
        assertTrue(games.isEmpty())
        assertTrue(repository.lastScanAllRead)

        // Como a pasta existe e pôde ser lida com sucesso, o cache é atualizado
        assertEquals(validEmptyDir.absolutePath, fakePrefs.getString("gamesCacheKey", null))
        assertEquals("[]", fakePrefs.getString("gamesCache", null))
    }

    @Test
    fun `varredura com lista vazia de diretorios grava cache vazio`() = runBlocking {
        val games = repository.scan(emptyList())
        assertTrue(games.isEmpty())
        assertTrue(repository.lastScanAllRead)
        assertEquals("", fakePrefs.getString("gamesCacheKey", null))
        assertEquals("[]", fakePrefs.getString("gamesCache", null))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            (data[key] as? Set<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        fun putString(key: String, value: String?) {
            data[key] = value
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = this
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) data.clear()
                pending.forEach { (k, v) ->
                    if (v === this) {
                        data.remove(k)
                    } else {
                        data[k] = v
                    }
                }
                pending.clear()
                clearFlag = false
            }
        }
    }
}
