package io.siggi.morganabot;

import io.siggi.morganabot.config.UserData;
import io.siggi.morganabot.util.Util;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeHandler {

    public static final Set<String> timezoneSet = new TreeSet<>();
    public static final Map<String, String> timezoneMap = new HashMap<>();

    static {
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(Util.getDataRoot(), "timezones.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                timezoneSet.add(line);
                timezoneMap.put(line.toLowerCase(Locale.ROOT), line);
            }
        } catch (Exception ignored) {
        }
    }

    private static final Pattern timePattern = Pattern.compile("([0-9]{1,2})(?:[:.]([0-9]{1,2})(?:[:.]([0-9]{1,2}))?)? ?(?:([ap])m?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern datePattern = Pattern.compile("(?:(?:([0-9]{4})[/\\- ])?([0-9]{1,2}|January|Jan|February|Feb|March|Mar|April|Apr|May|June|Jun|July|Jul|August|Aug|September|Sep|October|Oct|November|Nov|December|Dec)[/\\- ])?([0-9]{1,2}?)(?:st|nd|rd|th)?", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> parseMonth = new HashMap<>();

    static {
        parseMonth.put("january", 0);
        parseMonth.put("jan", 0);
        parseMonth.put("february", 1);
        parseMonth.put("feb", 1);
        parseMonth.put("march", 2);
        parseMonth.put("mar", 2);
        parseMonth.put("april", 3);
        parseMonth.put("apr", 3);
        parseMonth.put("may", 4);
        parseMonth.put("june", 5);
        parseMonth.put("jun", 5);
        parseMonth.put("july", 6);
        parseMonth.put("jul", 6);
        parseMonth.put("august", 7);
        parseMonth.put("aug", 7);
        parseMonth.put("september", 8);
        parseMonth.put("sep", 8);
        parseMonth.put("october", 9);
        parseMonth.put("oct", 9);
        parseMonth.put("november", 10);
        parseMonth.put("nov", 10);
        parseMonth.put("december", 11);
        parseMonth.put("dec", 11);
    }

    static void autocompleteTimezone(CommandAutoCompleteInteractionEvent event) {
        OptionMapping timezoneMapping = event.getOption("timezone");
        String enteredText = timezoneMapping == null ? "" : timezoneMapping.getAsString().toLowerCase(Locale.ROOT);
        List<String> timezones = new ArrayList<>();
        for (String timezone : timezoneSet) {
            if (timezones.size() >= 25) break;
            if (timezone.toLowerCase(Locale.ROOT).startsWith(enteredText)) {
                timezones.add(timezone);
            }
        }
        event.replyChoiceStrings(timezones).queue();
    }

    static void setTimezone(MorganaBot bot, SlashCommandInteractionEvent event) {
        OptionMapping timezoneMapping = event.getInteraction().getOption("timezone");
        String newTimezone = timezoneMapping == null ? "" : timezoneMapping.getAsString();
        String caseCorrectedTimezone = timezoneMap.get(newTimezone.toLowerCase(Locale.ROOT));
        if (newTimezone.isEmpty()) {
            event.reply("Set your timezone using the /timezone command!").setEphemeral(true).queue();
            return;
        } else if (caseCorrectedTimezone == null) {
            event.reply("The timezone you entered was not recognized!").setEphemeral(true).queue();
            return;
        }
        long idLong = event.getMember().getUser().getIdLong();
        UserData userData = bot.getUserData(idLong);
        userData.timezone = caseCorrectedTimezone;
        userData.save();
        event.reply("Your timezone was set to **" + caseCorrectedTimezone + "**.").setEphemeral(true).queue();
    }

    static void generateTime(MorganaBot bot, SlashCommandInteractionEvent event) {
        long now = System.currentTimeMillis();
        OptionMapping timeMapping = event.getInteraction().getOption("time");
        String time = timeMapping == null ? "" : timeMapping.getAsString();
        OptionMapping dateMapping = event.getInteraction().getOption("date");
        String date = dateMapping == null ? "" : dateMapping.getAsString();
        long idLong = event.getMember().getUser().getIdLong();
        boolean timezoneNotSet = false;
        String timezoneString = bot.getUserData(idLong).timezone;
        if (timezoneString == null) {
            timezoneNotSet = true;
            timezoneString = "UTC";
        }
        TimeZone timeZone = TimeZone.getTimeZone(timezoneString);
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeZone(timeZone);
        boolean clockHas24Hours, afternoon;
        Matcher timeMatcher = timePattern.matcher(time);
        if (!timeMatcher.matches()) {
            event.reply("I don't understand the time you entered!").setEphemeral(true).queue();
            return;
        }
        String hourString = timeMatcher.group(1);
        String minuteString = timeMatcher.group(2);
        String secondString = timeMatcher.group(3);
        String ampmString = timeMatcher.group(4);
        if (ampmString == null || ampmString.isEmpty()) {
            clockHas24Hours = true;
            afternoon = false;
        } else if (ampmString.equalsIgnoreCase("p")) {
            clockHas24Hours = false;
            afternoon = true;
        } else {
            clockHas24Hours = false;
            afternoon = false;
        }
        int hour = Integer.parseInt(hourString);
        int minute = minuteString == null ? 0 : Integer.parseInt(minuteString);
        int second = secondString == null ? 0 : Integer.parseInt(secondString);
        if (clockHas24Hours) {
            if (hour == 24) hour = 0;
        } else {
            if (hour > 12 || hour < 1) {
                event.reply("Hour must be between 1 and 12 on a 12 hour clock.").setEphemeral(true).queue();
                return;
            }
            if (hour == 12) hour = 0;
            if (afternoon) hour += 12;
        }
        if (minute < 0 || minute > 59 || second < 0 || second > 59) {
            event.reply("Minutes and seconds must be between 0 and 59.").setEphemeral(true).queue();
            return;
        }
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        Matcher dateMatcher = datePattern.matcher(date);
        if (date.isEmpty()) {
            if (calendar.getTimeInMillis() < now) {
                // if the time is in the past, make it tomorrow XD
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }
        } else if (!dateMatcher.matches()) {
            event.reply("I don't understand the date you entered! Please use year/month/day format.").setEphemeral(true).queue();
            return;
        } else {
            String yearString = dateMatcher.group(1);
            int year = yearString == null || yearString.isEmpty() ? -1 : Integer.parseInt(yearString);
            String monthString = dateMatcher.group(2);
            int month;
            if (monthString == null || monthString.isEmpty()) {
                month = -1;
            } else {
                try {
                    month = Integer.parseInt(monthString) - 1;
                } catch (Exception e) {
                    month = parseMonth.getOrDefault(monthString.toLowerCase(Locale.ROOT), -2);
                }
                if (month == -2 || month > 11) {
                    event.reply("I don't understand the month you entered!").setEphemeral(true).queue();
                    return;
                }
            }
            String dayString = dateMatcher.group(3);
            int day = Integer.parseInt(dayString);
            if (year != -1) {
                calendar.set(Calendar.YEAR, year);
            }
            if (month != -1) {
                calendar.set(Calendar.MONTH, month);
                if (year == -1) {
                    if (calendar.getTimeInMillis() < now) {
                        calendar.add(Calendar.YEAR, 1);
                    }
                }
            }
            calendar.set(Calendar.DAY_OF_MONTH, day);
            if (month == -1) {
                if (calendar.getTimeInMillis() < now) {
                    calendar.add(Calendar.MONTH, 1);
                }
            }
        }
        long timeInSeconds = calendar.getTimeInMillis() / 1000L;
        StringBuilder response = new StringBuilder();
        if (timezoneNotSet) {
            response.append("# HEY! You currently do not have a timezone set!\nSet your timezone with the `/timezone` command and then try this command again! Until then, it will be assumed you are in UTC timezone.\n\n");
        }
        response.append("**Timezone**: ").append(timezoneString).append("\n");
        if (!time.isEmpty()) {
            response.append("**Time**: ").append(time).append("\n");
        }
        if (!date.isEmpty()) {
            response.append("**Date**: ").append(date).append("\n");
        }
        response.append("\n");
        response.append("Copy/paste the code for the one you want into your message\n");
        Consumer<String> appender = (type) -> {
            StringBuilder formatBuilder = new StringBuilder();
            formatBuilder.append("<t:");
            formatBuilder.append(timeInSeconds);
            if (type != null) {
                formatBuilder.append(":");
                formatBuilder.append(type);
            }
            formatBuilder.append(">");
            String format = formatBuilder.toString();
            response.append("`").append(format).append("` ").append(format).append("\n");
        };
        appender.accept("t");
        appender.accept("T");
        appender.accept("d");
        appender.accept("D");
        appender.accept("f");
        appender.accept("F");
        appender.accept("R");
        event.reply(response.toString()).setEphemeral(true).queue();
    }
}
