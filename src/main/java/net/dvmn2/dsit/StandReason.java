package net.dvmn2.dsit;

/**
 * Почему игрок перестал сидеть.
 */
public enum StandReason {
    /** Игрок сам слез (shift). */
    DISMOUNT(true),
    /** Команда: повторный /sit или /ungrab. */
    COMMAND(true),
    /** Блок под сиденьем сломан/заменён/сдвинут или над ним появилось препятствие. */
    BLOCK_CHANGED(true),
    /** Игрок, на котором сидели, вышел, умер, телепортировался или исчез. */
    TARGET_LOST(true),
    /** Игрок вышел с сервера. */
    QUIT(true),
    /** Плагин выключается. */
    DISABLE(true),
    /** Игрока увели с сиденья извне (телепорт и т.п.): возвращать его обратно не нужно. */
    DETACHED(false),
    /** Игрок умер. */
    DEATH(false);

    private final boolean teleportsOut;

    StandReason(boolean teleportsOut) {
        this.teleportsOut = teleportsOut;
    }

    /** Нужно ли после слезания переместить игрока в безопасное место рядом с сиденьем. */
    public boolean teleportsOut() {
        return teleportsOut;
    }
}
