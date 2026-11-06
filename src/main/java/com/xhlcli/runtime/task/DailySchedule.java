package com.xhlcli.runtime.task;

import java.time.*;

/** 按固定时区的当地日历计算；缺失时间顺延，重复时间取较早偏移，每天一次。 */
public final class DailySchedule {
    private DailySchedule() {}
    private static LocalTime time(String value) {
        if(value==null||!value.matches("\\d{2}:\\d{2}"))throw new IllegalArgumentException("时间格式应为 HH:mm");
        try{return LocalTime.parse(value);}catch(DateTimeException e){throw new IllegalArgumentException("每日时间无效");}
    }
    private static ZoneId zone(String value) {
        try{return ZoneId.of(value);}catch(Exception e){throw new IllegalArgumentException("请选择有效时区");}
    }
    public static Instant next(String time,String zone,Instant after) {
        var t=time(time);var z=zone(zone);var date=after.atZone(z).toLocalDate();
        var candidate=date.atTime(t).atZone(z).toInstant();
        return candidate.isAfter(after)?candidate:date.plusDays(1).atTime(t).atZone(z).toInstant();
    }
    public static Instant latest(String time,String zone,Instant now) {
        var t=time(time);var z=zone(zone);var date=now.atZone(z).toLocalDate();
        var candidate=date.atTime(t).atZone(z).toInstant();
        return candidate.isAfter(now)?date.minusDays(1).atTime(t).atZone(z).toInstant():candidate;
    }
}
