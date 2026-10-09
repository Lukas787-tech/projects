package com.lukas.jarvis.ui.character

// Mochi's pixel art, one sheet per part, as authored. Each character is one
// pixel: . clear, o outline, b body, s shade, h shine, p blush, e eye,
// w glint, m steam, a accent, c paper, k cocoa, g sage, r berry, y honey.
// Composed into poses by [Sprites]; edit the art here, not the code there.

internal object Art {
    val BODY: Array<String> = arrayOf(
        "..........oooo..........",
        ".........obbbbo.........",
        ".......oobsbbsboo.......",
        ".....oobbsbbbbsbboo.....",
        "...oobbbbsbbbbsbbbboo...",
        "..obbbbbbbbbbbbbbbbbbo..",
        ".obbbbbbbbbbbbbbbbbbbbo.",
        ".obbhhbbbbbbbbbbbbbbbbo.",
        "obbhbbbbbbbbbbbbbbbbbbbo",
        "obbbbbbbbbbbbbbbbbbbbbbo",
        "obbbbbbbbbbbbbbbbbbbbbbo",
        "obbbbbbbbbbbbbbbbbbbbbso",
        "obbbbbbbbbbbbbbbbbbbbbso",
        "osbbbbbbbbbbbbbbbbbbbsso",
        "ossbbbbbbbbbbbbbbbbbssso",
        ".osssbbbbbbbbbbbbbbssso.",
        "..oossssssssssssssssoo..",
        "....oooooooooooooooo...."
    )

    val CURL_RELAXED: Array<String> = arrayOf(
        "...mm..",
        "..mm...",
        "..mm...",
        "...mm..",
        "...mm..",
        "..mm..."
    )

    val CURL_PERKED: Array<String> = arrayOf(
        "..mm...",
        ".mmmm..",
        ".mmmm..",
        "..mm...",
        "...mm..",
        "...mm..",
        "..mm...",
        "..mm...",
        "...mm.."
    )

    val CURL_DROOP: Array<String> = arrayOf(
        ".......",
        ".......",
        ".mm....",
        "..mm...",
        "...mmm.",
        ".....m."
    )

    val CURL_QUESTION: Array<String> = arrayOf(
        ".mmmm..",
        "mm..mm.",
        "....mm.",
        "...mm..",
        "..mm...",
        ".......",
        "..mm..."
    )

    val CURL_BANG: Array<String> = arrayOf(
        "..mm...",
        "..mm...",
        "..mm...",
        "..mm...",
        ".......",
        "..mm..."
    )

    val CURL_HEART: Array<String> = arrayOf(
        ".rr.rr.",
        "rrrrrrr",
        "rrrrrrr",
        ".rrrrr.",
        "..rrr..",
        "...r..."
    )

    val CURL_STAR: Array<String> = arrayOf(
        "...y...",
        "..yyy..",
        "yyyyyyy",
        "..yyy..",
        "...y..."
    )

    val CURL_Z: Array<String> = arrayOf(
        "mmmm",
        "..m.",
        ".m..",
        "mmmm"
    )

    val PROP_NOTEPAD: Array<String> = arrayOf(
        ".oooooooo..",
        ".occcccco.a",
        ".okkkkkco.a",
        ".occcccco.a",
        ".okkkkcco.a",
        ".occcccco.k",
        ".okkkcccok.",
        ".oooooooo.."
    )

    val PROP_COINS: Array<String> = arrayOf(
        "...oooo....",
        "..oyyyyo...",
        "..oyaayo...",
        "..oyyyyo...",
        "oooooooooo.",
        "oyyyyyyyyo.",
        "oyaaaaaayo.",
        "oyyyyyyyyo.",
        "oooooooooo."
    )

    val PROP_MAGNIFIER: Array<String> = arrayOf(
        "..oooo.....",
        ".ocmmco....",
        "ocmhmmco...",
        "ocmmmmco...",
        "ocmmmmco...",
        ".ocmmco....",
        "..ooooko...",
        "......okko.",
        ".......oko.",
        "........o.."
    )

    val PROP_UMBRELLA: Array<String> = arrayOf(
        "....oo.....",
        "..oaaaao...",
        ".oaaaaaao..",
        "oaaacaaaao.",
        "oooooooooo.",
        "....ok.....",
        "....ok.....",
        "....ok..o..",
        ".....ookk.."
    )

    val PROP_MAP: Array<String> = arrayOf(
        "oooooooooo.",
        "ocyyccggco.",
        "ocyccgggco.",
        "occrccggco.",
        "occcrcccco.",
        "ocggccrcco.",
        "ocggcccayo.",
        "oooooooooo."
    )

    val PROP_CALENDAR: Array<String> = arrayOf(
        ".o..o..o...",
        "oaoaoaoao..",
        "oaaaaaaaao.",
        "occcccccco.",
        "ockckckcco.",
        "occcccccco.",
        "ockckcrcco.",
        "occcccccco.",
        "oooooooooo."
    )

    val PROP_ENVELOPE: Array<String> = arrayOf(
        "oooooooooo.",
        "ommcccccmo.",
        "ocommmmcoo.",
        "occoccocco.",
        "occcooccco.",
        "occcccccco.",
        "oooooooooo."
    )

    val PROP_PHONE: Array<String> = arrayOf(
        ".oooooo....",
        ".okkkko....",
        ".ocmmco....",
        ".ocmmco....",
        ".ocmmco....",
        ".ocmmco....",
        ".okkkko....",
        ".okakko....",
        ".oooooo...."
    )

    val PROP_NOTE: Array<String> = arrayOf(
        "....oooo...",
        "....oaao...",
        "....oo.o...",
        "....o..o...",
        "....o..o...",
        ".ooo..oo...",
        "oaaao......",
        "oaaao......",
        ".ooo......."
    )

    val PROP_CAMERA: Array<String> = arrayOf(
        "...ooo.....",
        "oooaaaoooo.",
        "okkkkkkkko.",
        "okkoookkko.",
        "okocmcokko.",
        "okocmcokko.",
        "okkoookkko.",
        "oooooooooo."
    )

    val PROP_GEAR: Array<String> = arrayOf(
        "...oo.oo...",
        "..oaoaoao..",
        ".oaaaaaaao.",
        "ooaaoooaao.",
        ".oaaocoaao.",
        ".oaaoooaaoo",
        ".oaaaaaaao.",
        "..oaoaoao..",
        "...oo.oo..."
    )

    val PROP_BUBBLE: Array<String> = arrayOf(
        ".oooooooo..",
        "occcccccco.",
        "ockkcckkco.",
        "occcccccco.",
        "occckkccco.",
        ".ooocoooo..",
        "...oco.....",
        "...oo......"
    )

    val PROP_BRUSH: Array<String> = arrayOf(
        ".......oo..",
        "......oaao.",
        ".....oaao..",
        "....okko...",
        "...okko....",
        "..okko.....",
        ".oyyo......",
        "oyyyo......",
        ".oo........"
    )

    val PROP_DIE: Array<String> = arrayOf(
        ".oooooooo.",
        "occcccccco",
        "ockcccckco",
        "occcccccco",
        "occcckccco",
        "occcccccco",
        "ockcccckco",
        "occcccccco",
        ".oooooooo."
    )

    val PROP_HOUSE: Array<String> = arrayOf(
        "....oo.....",
        "...oaao....",
        "..oaaaao...",
        ".oaaaaaao..",
        "oooooooooo.",
        ".occcccco..",
        ".ockcckco..",
        ".occcookco.",
        ".oooooooo.."
    )

    val PROP_BELL: Array<String> = arrayOf(
        "....oo.....",
        "...oyyo....",
        "..oyyyyo...",
        "..oyyyyo...",
        "..oyyyyo...",
        ".oyyyyyyo..",
        "oooooooooo.",
        "....oo....."
    )

    val PROP_CUP: Array<String> = arrayOf(
        "..m.m.....",
        "...m.m....",
        ".ooooooo..",
        ".occcccooo",
        ".oaaaaao.o",
        ".oaaaaaooo",
        "..oaaao...",
        "...ooo...."
    )

    val PROP_CARD: Array<String> = arrayOf(
        "oooooooooooo",
        "occcccccccco",
        "ockkkkkkkkco",
        "occcccccccco",
        "ockkkkkkccco",
        "occcccccccco",
        "ocaaaaccccco",
        "oooooooooooo"
    )

    val EYES_OPEN: Array<String> = arrayOf(
        ".ee.",
        "ewee",
        "eeee",
        ".ee."
    )

    val EYES_BLINK: Array<String> = arrayOf(
        "....",
        "....",
        "eeee",
        "...."
    )

    val EYES_HAPPY: Array<String> = arrayOf(
        "....",
        ".ee.",
        "e..e",
        "...."
    )

    val EYES_CLOSED: Array<String> = arrayOf(
        "....",
        "....",
        "e..e",
        ".ee."
    )

    val EYES_WIDE: Array<String> = arrayOf(
        ".ee.",
        "ewwe",
        "eeee",
        ".ee."
    )

    val BROW_WORRIED_LEFT: Array<String> = arrayOf(
        "..ee",
        "ee.."
    )

    val BROW_WORRIED_RIGHT: Array<String> = arrayOf(
        "ee..",
        "..ee"
    )

    val BROW_RAISED: Array<String> = arrayOf(
        "eeee",
        "...."
    )

    val MOUTH_SMILE: Array<String> = arrayOf(
        "e..e",
        ".ee."
    )

    val MOUTH_FLAT: Array<String> = arrayOf(
        ".ee."
    )

    val MOUTH_SMALL: Array<String> = arrayOf(
        ".ee.",
        "eppe",
        ".ee."
    )

    val MOUTH_OPEN: Array<String> = arrayOf(
        "eeee",
        "eppe",
        "eppe",
        ".ee."
    )

    val MOUTH_FROWN: Array<String> = arrayOf(
        ".ee.",
        "e..e"
    )

    val MOUTH_GRIN: Array<String> = arrayOf(
        "eeee",
        "eppe",
        ".ee."
    )

    val CURL_LEAN_LEFT: Array<String> = arrayOf(
        "..mm...",
        ".mm....",
        ".mm....",
        "..mm...",
        "...mm..",
        "..mm..."
    )

    val CURL_LEAN_RIGHT: Array<String> = arrayOf(
        "....mm.",
        ".....mm",
        ".....mm",
        "....mm.",
        "...mm..",
        "..mm..."
    )

    val ARM: Array<String> = arrayOf(
        ".oo.",
        "obbo",
        "obbo",
        ".oo."
    )

    val TINY: Array<String> = arrayOf(
        "................",
        "................",
        "......mm........",
        ".....mm.........",
        "......mm........",
        "......oooo......",
        "....oobsbsoo....",
        "..oobbbbbbbboo..",
        ".obbbbbbbbbbbbo.",
        "obbbeebbbbeebbbo",
        "obbbeebbbbeebbbo",
        "obpbbbbeebbbbpbo",
        "obbbbbbbbbbbbbso",
        ".osbbbbbbbbbbso.",
        "..oossssssssoo..",
        "....oooooooo...."
    )

    val ARM_LEFT: Array<String> = arrayOf(
        ".ooo",
        "obbb",
        "obbb",
        ".ooo"
    )

    val ARM_RIGHT: Array<String> = arrayOf(
        "ooo.",
        "bbbo",
        "bbbo",
        "ooo."
    )
}
