from pathlib import Path

path = Path('app/src/main/java/com/ling20/translator/CameraOcr.kt')
text = path.read_text()

old = '''            val confidence = orderedWords.sumOf {
                it.confidence * it.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
            } / charWeight
'''
new = '''            val confidenceSum = orderedWords.fold(0f) { sum, item ->
                sum + item.confidence * item.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
            }
            val confidence = confidenceSum / charWeight.toFloat()
'''
if old not in text:
    raise SystemExit('confidence pattern not found')
text = text.replace(old, new, 1)

old = '''        val first = clean.firstOrNull()
        val needsSpace = builder.isNotEmpty() &&
            !(previous?.isHanCharacter() == true && first?.isHanCharacter() == true) &&
            first !in charArrayOf(',', '.', ':', ';', '!', '?', '%', ')', ']', '}', '，', '。', '：', '；', '！', '？')
'''
new = '''        val first = clean.first()
        val needsSpace = builder.isNotEmpty() &&
            !(previous?.isHanCharacter() == true && first.isHanCharacter()) &&
            first !in charArrayOf(',', '.', ':', ';', '!', '?', '%', ')', ']', '}', '，', '。', '：', '；', '！', '？')
'''
if old not in text:
    raise SystemExit('first-char pattern not found')
text = text.replace(old, new, 1)

path.write_text(text)
