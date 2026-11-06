package app.gifitup.shared.data.content

/**
 * The emojis a reaction may carry (`ann_reaction`, ADR-0006 B10): 1156 single-symbol emojis of Emoji 11.0 or older, each
 * written as the exact string the table holds (with U+FE0F where the emoji needs it), no skin tones, flags, keycaps or
 * joiner sequences. **Part of the content format: never edit or reorder.** `shared/emoji-reactions-v1.tsv` holds the same
 * list and its SHA-256 is pinned in a test (`ReactionTableFileTest`), so neither can change alone. A different list is a
 * new file (`v2`) and a new content version.
 *
 * A received reaction is accepted only if its emoji equals an entry exactly, and what is stored and drawn is the entry
 * itself, never the received string (ADR-0006 B10).
 */
object ReactionEmojis {
    const val SIZE = 1156

    /** The table in file order. */
    val all: List<String> =
        listOf(
            "⌚ ⌛ ⏰ ⏱️ ⏲️ ⏳ ☀️ ☁️ ☂️ ☃️ ☄️ ☎️ ☑️ ☔ ☕ ☘️ ☝️ ☠️ ☢️ ☣️ ☦️ ☪️ ☮️ ☯️",
            "☸️ ☹️ ☺️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ♟️ ♠️ ♣️ ♥️ ♦️ ♨️ ♻️ ♾️ ♿",
            "⚒️ ⚓ ⚔️ ⚖️ ⚗️ ⚙️ ⚛️ ⚜️ ⚠️ ⚡ ⚪ ⚫ ⚰️ ⚱️ ⚽ ⚾ ⛄ ⛅ ⛈️ ⛎ ⛏️ ⛑️ ⛓️ ⛔",
            "⛩️ ⛪ ⛰️ ⛱️ ⛲ ⛳ ⛴️ ⛵ ⛷️ ⛸️ ⛹️ ⛺ ⛽ ✂️ ✅ ✈️ ✉️ ✊ ✋ ✌️ ✍️ ✏️ ✒️ ✔️",
            "✖️ ✝️ ✡️ ✨ ✳️ ✴️ ❄️ ❇️ ❌ ❎ ❓ ❔ ❕ ❗ ❣️ ❤️ ➕ ➖ ➗ ➡️ ➰ ➿ ⬛ ⬜",
            "⭐ ⭕ 🀄 🃏 🅰️ 🅱️ 🅾️ 🅿️ 🆎 🆑 🆒 🆓 🆔 🆕 🆖 🆗 🆘 🆙 🆚 🈁 🈂️ 🈚 🈯 🈲",
            "🈳 🈴 🈵 🈶 🈷️ 🈸 🈹 🈺 🉐 🉑 🌀 🌁 🌂 🌃 🌄 🌅 🌆 🌇 🌈 🌉 🌊 🌋 🌌 🌍",
            "🌎 🌏 🌐 🌑 🌒 🌓 🌔 🌕 🌖 🌗 🌘 🌙 🌚 🌛 🌜 🌝 🌞 🌟 🌠 🌡️ 🌤️ 🌥️ 🌦️ 🌧️",
            "🌨️ 🌩️ 🌪️ 🌫️ 🌬️ 🌭 🌮 🌯 🌰 🌱 🌲 🌳 🌴 🌵 🌶️ 🌷 🌸 🌹 🌺 🌻 🌼 🌽 🌾 🌿",
            "🍀 🍁 🍂 🍃 🍄 🍅 🍆 🍇 🍈 🍉 🍊 🍋 🍌 🍍 🍎 🍏 🍐 🍑 🍒 🍓 🍔 🍕 🍖 🍗",
            "🍘 🍙 🍚 🍛 🍜 🍝 🍞 🍟 🍠 🍡 🍢 🍣 🍤 🍥 🍦 🍧 🍨 🍩 🍪 🍫 🍬 🍭 🍮 🍯",
            "🍰 🍱 🍲 🍳 🍴 🍵 🍶 🍷 🍸 🍹 🍺 🍻 🍼 🍽️ 🍾 🍿 🎀 🎁 🎂 🎃 🎄 🎅 🎆 🎇",
            "🎈 🎉 🎊 🎋 🎌 🎍 🎎 🎏 🎐 🎑 🎒 🎓 🎖️ 🎗️ 🎙️ 🎚️ 🎛️ 🎞️ 🎟️ 🎠 🎡 🎢 🎣 🎤",
            "🎥 🎦 🎧 🎨 🎩 🎪 🎫 🎬 🎭 🎮 🎯 🎰 🎱 🎲 🎳 🎴 🎵 🎶 🎷 🎸 🎹 🎺 🎻 🎼",
            "🎽 🎾 🎿 🏀 🏁 🏂 🏃 🏄 🏅 🏆 🏇 🏈 🏉 🏊 🏋️ 🏌️ 🏍️ 🏎️ 🏏 🏐 🏑 🏒 🏓 🏔️",
            "🏕️ 🏖️ 🏗️ 🏘️ 🏙️ 🏚️ 🏛️ 🏜️ 🏝️ 🏞️ 🏟️ 🏠 🏡 🏢 🏣 🏤 🏥 🏦 🏧 🏨 🏩 🏪 🏫 🏬",
            "🏭 🏮 🏯 🏰 🏳️ 🏴 🏵️ 🏷️ 🏸 🏹 🏺 🐀 🐁 🐂 🐃 🐄 🐅 🐆 🐇 🐈 🐉 🐊 🐋 🐌",
            "🐍 🐎 🐏 🐐 🐑 🐒 🐓 🐔 🐕 🐖 🐗 🐘 🐙 🐚 🐛 🐜 🐝 🐞 🐟 🐠 🐡 🐢 🐣 🐤",
            "🐥 🐦 🐧 🐨 🐩 🐪 🐫 🐬 🐭 🐮 🐯 🐰 🐱 🐲 🐳 🐴 🐵 🐶 🐷 🐸 🐹 🐺 🐻 🐼",
            "🐽 🐾 🐿️ 👀 👁️ 👂 👃 👄 👅 👆 👇 👈 👉 👊 👋 👌 👍 👎 👏 👐 👑 👒 👓 👔",
            "👕 👖 👗 👘 👙 👚 👛 👜 👝 👞 👟 👠 👡 👢 👣 👤 👥 👦 👧 👨 👩 👪 👫 👬",
            "👭 👮 👯 👰 👱 👲 👳 👴 👵 👶 👷 👸 👹 👺 👻 👼 👽 👾 👿 💀 💁 💂 💃 💄",
            "💅 💆 💇 💈 💉 💊 💋 💌 💍 💎 💏 💐 💑 💒 💓 💔 💕 💖 💗 💘 💙 💚 💛 💜",
            "💝 💞 💟 💠 💡 💢 💣 💤 💥 💦 💧 💨 💩 💪 💫 💬 💭 💮 💯 💰 💱 💲 💳 💴",
            "💵 💶 💷 💸 💹 💺 💻 💼 💽 💾 💿 📀 📁 📂 📃 📄 📅 📆 📇 📈 📉 📊 📋 📌",
            "📍 📎 📏 📐 📑 📒 📓 📔 📕 📖 📗 📘 📙 📚 📛 📜 📝 📞 📟 📠 📡 📢 📣 📤",
            "📥 📦 📧 📨 📩 📪 📫 📬 📭 📮 📯 📰 📱 📲 📳 📴 📵 📶 📷 📸 📹 📺 📻 📼",
            "📽️ 📿 🔀 🔁 🔂 🔃 🔄 🔅 🔆 🔇 🔈 🔉 🔊 🔋 🔌 🔍 🔎 🔏 🔐 🔑 🔒 🔓 🔔 🔕",
            "🔖 🔗 🔘 🔙 🔚 🔛 🔜 🔝 🔞 🔟 🔠 🔡 🔢 🔣 🔤 🔥 🔦 🔧 🔨 🔩 🔪 🔫 🔬 🔭",
            "🔮 🔯 🔰 🔱 🔲 🔳 🔴 🔵 🔶 🔷 🔸 🔹 🔺 🔻 🔼 🔽 🕉️ 🕊️ 🕋 🕌 🕍 🕎 🕐 🕑",
            "🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛 🕜 🕝 🕞 🕟 🕠 🕡 🕢 🕣 🕤 🕥 🕦 🕧 🕯️ 🕰️",
            "🕳️ 🕴️ 🕵️ 🕶️ 🕷️ 🕸️ 🕹️ 🕺 🖇️ 🖊️ 🖋️ 🖌️ 🖍️ 🖐️ 🖕 🖖 🖤 🖥️ 🖨️ 🖱️ 🖲️ 🖼️ 🗂️ 🗃️",
            "🗄️ 🗑️ 🗒️ 🗓️ 🗜️ 🗝️ 🗞️ 🗡️ 🗣️ 🗨️ 🗯️ 🗳️ 🗺️ 🗻 🗼 🗽 🗾 🗿 😀 😁 😂 😃 😄 😅",
            "😆 😇 😈 😉 😊 😋 😌 😍 😎 😏 😐 😑 😒 😓 😔 😕 😖 😗 😘 😙 😚 😛 😜 😝",
            "😞 😟 😠 😡 😢 😣 😤 😥 😦 😧 😨 😩 😪 😫 😬 😭 😮 😯 😰 😱 😲 😳 😴 😵",
            "😶 😷 😸 😹 😺 😻 😼 😽 😾 😿 🙀 🙁 🙂 🙃 🙄 🙅 🙆 🙇 🙈 🙉 🙊 🙋 🙌 🙍",
            "🙎 🙏 🚀 🚁 🚂 🚃 🚄 🚅 🚆 🚇 🚈 🚉 🚊 🚋 🚌 🚍 🚎 🚏 🚐 🚑 🚒 🚓 🚔 🚕",
            "🚖 🚗 🚘 🚙 🚚 🚛 🚜 🚝 🚞 🚟 🚠 🚡 🚢 🚣 🚤 🚥 🚦 🚧 🚨 🚩 🚪 🚫 🚬 🚭",
            "🚮 🚯 🚰 🚱 🚲 🚳 🚴 🚵 🚶 🚷 🚸 🚹 🚺 🚻 🚼 🚽 🚾 🚿 🛀 🛁 🛂 🛃 🛄 🛅",
            "🛋️ 🛌 🛍️ 🛎️ 🛏️ 🛐 🛑 🛒 🛠️ 🛡️ 🛢️ 🛣️ 🛤️ 🛥️ 🛩️ 🛫 🛬 🛰️ 🛳️ 🛴 🛵 🛶 🛷 🛸",
            "🛹 🤐 🤑 🤒 🤓 🤔 🤕 🤖 🤗 🤘 🤙 🤚 🤛 🤜 🤝 🤞 🤟 🤠 🤡 🤢 🤣 🤤 🤥 🤦",
            "🤧 🤨 🤩 🤪 🤫 🤬 🤭 🤮 🤯 🤰 🤱 🤲 🤳 🤴 🤵 🤶 🤷 🤸 🤹 🤺 🤼 🤽 🤾 🥀",
            "🥁 🥂 🥃 🥄 🥅 🥇 🥈 🥉 🥊 🥋 🥌 🥍 🥎 🥏 🥐 🥑 🥒 🥓 🥔 🥕 🥖 🥗 🥘 🥙",
            "🥚 🥛 🥜 🥝 🥞 🥟 🥠 🥡 🥢 🥣 🥤 🥥 🥦 🥧 🥨 🥩 🥪 🥫 🥬 🥭 🥮 🥯 🥰 🥳",
            "🥴 🥵 🥶 🥺 🥼 🥽 🥾 🥿 🦀 🦁 🦂 🦃 🦄 🦅 🦆 🦇 🦈 🦉 🦊 🦋 🦌 🦍 🦎 🦏",
            "🦐 🦑 🦒 🦓 🦔 🦕 🦖 🦗 🦘 🦙 🦚 🦛 🦜 🦝 🦞 🦟 🦠 🦡 🦢 🦴 🦵 🦶 🦷 🦸",
            "🦹 🧀 🧁 🧂 🧐 🧑 🧒 🧓 🧔 🧕 🧖 🧗 🧘 🧙 🧚 🧛 🧜 🧝 🧞 🧟 🧠 🧡 🧢 🧣",
            "🧤 🧥 🧦 🧧 🧨 🧩 🧪 🧫 🧬 🧭 🧮 🧯 🧰 🧱 🧲 🧳 🧴 🧵 🧶 🧷 🧸 🧹 🧺 🧻",
            "🧼 🧽 🧾 🧿",
        ).flatMap { it.split(' ') }

    private val known: Set<String> = all.toSet()

    /** The table's own entry for [text], or `null` if [text] is not exactly one entry. */
    fun canonical(text: String): String? = text.takeIf { it in known }
}
