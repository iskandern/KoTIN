package dev.iskandern.kotin.model

import kotlin.concurrent.Volatile

/**
 * Журнал разбора: читатели пишут сообщения ключами [UiText], а куда и на каком языке их вывести, решает [sink].
 * Приложение ставит вывод с переводом, Web Worker — пересылку в главный поток; по умолчанию — печать как есть.
 */
object Journal {
  @Volatile
  var sink: (String) -> Unit = ::println

  fun log(text: String) = sink(text)
}
