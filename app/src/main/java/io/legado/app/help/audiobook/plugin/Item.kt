package io.legado.app.help.audiobook.plugin

data class Item(val name: String, val value: Any) {
    companion object {
        @JvmStatic
        fun create(name: String, value: Any): Item = Item(name, value)
    }
}
