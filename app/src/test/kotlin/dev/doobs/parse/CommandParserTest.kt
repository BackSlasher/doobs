package dev.doobs.parse

import dev.doobs.convert.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class CommandParserTest {

    /** A fixed "now" — mid-morning, so bare hours have an unambiguous next occurrence. */
    private val now = LocalDateTime.of(2026, 9, 30, 9, 15)

    private fun parse(text: String) = CommandParser.parse(text, now)

    private fun alarm(text: String): Command.SetAlarm {
        val parsed = parse(text)
        assertTrue("'$text' was not understood", parsed is Parsed.Understood)
        val command = (parsed as Parsed.Understood).command
        assertTrue("'$text' became $command", command is Command.SetAlarm)
        return command as Command.SetAlarm
    }

    private fun remind(text: String): Command.Remind {
        val parsed = parse(text)
        assertTrue("'$text' was not understood", parsed is Parsed.Understood)
        val command = (parsed as Parsed.Understood).command
        assertTrue("'$text' became $command", command is Command.Remind)
        return command as Command.Remind
    }

    // --- alarms ------------------------------------------------------------

    @Test
    fun `digits with a colon`() {
        assertEquals(LocalTime.of(19, 30), alarm("set an alarm for 7:30 pm").at)
    }

    @Test
    fun `spoken numbers`() {
        assertEquals(LocalTime.of(7, 30), alarm("set an alarm for seven thirty am").at)
        assertEquals(LocalTime.of(19, 5), alarm("set an alarm for seven oh five pm").at)
        assertEquals(LocalTime.of(6, 45), alarm("set an alarm for six forty five am").at)
    }

    @Test
    fun `bare hour picks the next time it comes round`() {
        // 09:15 now, so "seven" means tonight and "eleven" means this morning.
        assertEquals(LocalTime.of(19, 0), alarm("set an alarm for seven").at)
        assertEquals(LocalTime.of(11, 0), alarm("set an alarm for eleven").at)
        assertEquals(LocalTime.of(19, 30), alarm("set an alarm for seven thirty").at)
    }

    @Test
    fun `explicit meridiem wins over the next-occurrence guess`() {
        assertEquals(LocalTime.of(7, 0), alarm("set an alarm for 7 am").at)
        assertEquals(LocalTime.of(8, 0), alarm("set an alarm for 8 in the morning").at)
        assertEquals(LocalTime.of(20, 0), alarm("set an alarm for 8 tonight").at)
    }

    @Test
    fun `past and to`() {
        assertEquals(LocalTime.of(18, 15), alarm("alarm for quarter past six").at)
        assertEquals(LocalTime.of(19, 45), alarm("wake me up at quarter to eight pm").at)
        assertEquals(LocalTime.of(18, 30), alarm("alarm for half past six").at)
        assertEquals(LocalTime.of(19, 50), alarm("set an alarm for ten to eight pm").at)
    }

    @Test
    fun `twenty four hour and military style`() {
        assertEquals(LocalTime.of(7, 30), alarm("set an alarm for 0730").at)
        assertEquals(LocalTime.of(19, 45), alarm("set an alarm for 1945").at)
        assertEquals(LocalTime.of(14, 0), alarm("set an alarm for 14").at)
    }

    @Test
    fun `named times`() {
        assertEquals(LocalTime.NOON, alarm("set an alarm for noon").at)
        assertEquals(LocalTime.MIDNIGHT, alarm("set an alarm for midnight").at)
    }

    @Test
    fun `wake me up and politeness`() {
        assertEquals(LocalTime.of(6, 0), alarm("hey doobs please set an alarm for 6 am").at)
        assertEquals(LocalTime.of(6, 0), alarm("wake me up at 6 am").at)
        assertEquals(LocalTime.of(6, 0), alarm("could you set an alarm for six in the morning").at)
    }

    @Test
    fun `tomorrow is ignored because the clock app picks the next occurrence`() {
        assertEquals(LocalTime.of(7, 0), alarm("set an alarm for 7 am tomorrow").at)
    }

    @Test
    fun `alarm can carry a label`() {
        val command = alarm("set an alarm for 6 am to go running")
        assertEquals(LocalTime.of(6, 0), command.at)
        assertEquals("go running", command.label)
    }

    @Test
    fun `a relative alarm resolves to a wall clock time`() {
        assertEquals(LocalTime.of(9, 45), alarm("set an alarm in 30 minutes").at)
    }

    // --- reminders ---------------------------------------------------------

    @Test
    fun `time first`() {
        val command = remind("remind me at 6 to take the bins out")
        assertEquals("take the bins out", command.text)
        assertEquals(Moment.At(LocalTime.of(18, 0)), command.moment)
    }

    @Test
    fun `text first`() {
        val command = remind("remind me to call the dentist at 9 am")
        assertEquals("call the dentist", command.text)
        assertEquals(Moment.At(LocalTime.of(9, 0)), command.moment)
    }

    @Test
    fun `text containing at keeps the rightmost time`() {
        val command = remind("remind me to look at the oven in 10 minutes")
        assertEquals("look at the oven", command.text)
        assertEquals(Moment.After(Duration.ofMinutes(10)), command.moment)
    }

    @Test
    fun `durations`() {
        assertEquals(Moment.After(Duration.ofMinutes(20)), remind("remind me in 20 minutes to check the oven").moment)
        assertEquals(Moment.After(Duration.ofMinutes(30)), remind("remind me in half an hour to stretch").moment)
        assertEquals(Moment.After(Duration.ofMinutes(90)), remind("remind me in an hour and a half to stretch").moment)
        assertEquals(Moment.After(Duration.ofHours(2)), remind("remind me in 2 hours to stretch").moment)
        assertEquals(Moment.After(Duration.ofMinutes(5)), remind("remind me in 5 to stretch").moment)
    }

    @Test
    fun `set a reminder phrasing`() {
        val command = remind("set a reminder for 5 pm to call mum")
        assertEquals("call mum", command.text)
        assertEquals(Moment.At(LocalTime.of(17, 0)), command.moment)
    }

    @Test
    fun `no to at all`() {
        val command = remind("remind me buy milk at 5 pm")
        assertEquals("buy milk", command.text)
        assertEquals(Moment.At(LocalTime.of(17, 0)), command.moment)
    }

    // --- parking -----------------------------------------------------------

    @Test
    fun `remembering the spot`() {
        val said = listOf(
            "i parked here",
            "i just parked",
            "parked here",
            "i'm parked here",
            "this is where i parked",
            "remember where i parked",
            "save my parking spot",
            "mark my car",
        )
        for (text in said) {
            assertEquals("'$text'", Command.ParkHere, (parse(text) as? Parsed.Understood)?.command)
        }
    }

    @Test
    fun `finding the car again`() {
        val said = listOf(
            "take me to my car",
            "take me to car",
            "take me back to my car",
            "navigate to my car",
            "where's my car",
            "where is my car",
            "where did i park",
            "where am i parked",
            "find my car",
        )
        for (text in said) {
            assertEquals("'$text'", Command.FindCar, (parse(text) as? Parsed.Understood)?.command)
        }
    }

    @Test
    fun `parking does not swallow the time grammars`() {
        // "park" appearing in a reminder is a task, not a parking command.
        val command = remind("remind me at 6 to park the car")
        assertEquals("park the car", command.text)
        assertEquals(Moment.At(LocalTime.of(18, 0)), command.moment)
    }

    @Test
    fun `an alarm with no time opens the clock`() {
        val said = listOf("set an alarm", "set alarm", "new alarm", "add an alarm", "alarm", "show my alarms")
        for (text in said) {
            assertEquals("'$text'", Command.NewAlarm, (parse(text) as? Parsed.Understood)?.command)
        }
    }

    @Test
    fun `a bare alarm never shadows one with a time`() {
        // BARE_ALARM is tried last precisely so these still win.
        assertEquals(LocalTime.of(19, 30), alarm("set an alarm for 7:30 pm").at)
        assertEquals(LocalTime.of(6, 0), alarm("set an alarm for 6 am").at)
        assertEquals("go running", alarm("set an alarm for 6 am to go running").label)
    }

    @Test
    fun `opening translate`() {
        val said = listOf("translate", "open translate", "google translate", "open google translate", "translator")
        for (text in said) {
            assertEquals("'$text'", Command.Translate, (parse(text) as? Parsed.Understood)?.command)
        }
    }

    // --- conversions -------------------------------------------------------

    private fun convert(text: String): Command.Convert {
        val parsed = parse(text)
        assertTrue("'$text' was not understood", parsed is Parsed.Understood)
        val command = (parsed as Parsed.Understood).command
        assertTrue("'$text' became $command", command is Command.Convert)
        return command as Command.Convert
    }

    private fun result(text: String): String {
        val c = convert(text)
        return Units.format(Units.convert(c.amount, c.from, c.to)!!) + " " + c.to.symbol
    }

    @Test
    fun `conversion phrasings`() {
        assertEquals("4.331 in", result("convert 11 cm to inches"))
        assertEquals("4.331 in", result("convert 11 centimeters to inches"))
        assertEquals("4.331 in", result("11 cm in inches"))
        assertEquals("4.331 in", result("what's 11 cm in inches"))
        assertEquals("4.331 in", result("how many inches in 11 cm"))
    }

    @Test
    fun `in is both a unit and a separator`() {
        // Dictation writes inches as "in", which is also one of the words that
        // separates the two halves. Splitting on the first one gives amount "1"
        // and target "to cm"; the split on "to" is the right one.
        assertEquals("2.54 cm", result("convert 1 in to cm"))
        assertEquals("2.54 cm", result("1 in to cm"))
        assertEquals("30.48 cm", result("convert 12 in to cm"))
        // And the separator must still not be found inside a longer word.
        assertEquals("2.54 cm", result("convert 1 inch to cm"))
        assertEquals("4.331 in", result("convert 11 cm to in"))
    }

    @Test
    fun `an unspoken one`() {
        // "convert an inch to cm" carries no digit at all.
        assertEquals("2.54 cm", result("convert an inch to cm"))
        assertEquals("2.54 cm", result("convert a inch to cm"))
    }

    @Test
    fun `decimals survive normalisation`() {
        // Words.normalize strips full stops, which would turn 1.5 into "1 5".
        assertEquals(1.5, convert("convert 1.5 kg to pounds").amount, 0.0001)
        assertEquals("3.307 lb", result("convert 1.5 kg to pounds"))
    }

    @Test
    fun `spoken numbers convert too`() {
        assertEquals("25.4 mm", result("convert one inch to millimetres"))
    }

    @Test
    fun `temperature is not a ratio`() {
        assertEquals("32 °F", result("convert 0 celsius to fahrenheit"))
        assertEquals("100 °C", result("convert 212 fahrenheit to celsius"))
        assertEquals("273.1 K", result("convert 0 celsius to kelvin"))
    }

    @Test
    fun `mismatched units are refused rather than guessed`() {
        assertTrue(parse("convert 11 cm to kilograms") is Parsed.NotUnderstood)
        assertTrue(parse("convert 11 bananas to inches") is Parsed.NotUnderstood)
    }

    @Test
    fun `conversion grammar does not steal time commands`() {
        // "<something> to <something>" is also the shape of a labelled alarm and
        // of a reminder, so the converter only wins when both units resolve.
        assertEquals(LocalTime.of(19, 50), alarm("set an alarm for ten to eight pm").at)
        assertEquals("take the bins out", remind("remind me at 6 to take the bins out").text)
        assertEquals(LocalTime.of(7, 30), alarm("set an alarm for 7.30 am").at)
    }

    // --- refusals ----------------------------------------------------------

    @Test
    fun `things doobs does not do`() {
        val rejected = listOf(
            "",
            "what's the weather",
            "play some music",
            "remind me to call the dentist", // no time given
            "turn on the lights",
        )
        for (text in rejected) {
            assertTrue("'$text' should not have parsed: ${parse(text)}", parse(text) is Parsed.NotUnderstood)
        }
    }

    @Test
    fun `the transcript comes back for display`() {
        val parsed = parse("Set an alarm for 7:30 PM.") as Parsed.Understood
        assertEquals("set an alarm for 7:30 pm", parsed.heard)
    }
}
