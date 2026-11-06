package com.xhlcli.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** 可选的持久化开关；先落盘再应用，多个工作区按单项合并，避免相互覆盖。 */
final class SkillPreferences {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private SkillPreferences() {}
    static synchronized Map<String,Boolean> read(Path file) {
        if(Files.notExists(file))return Map.of();
        try {
            var value=MAPPER.readTree(file.toFile());
            if(value==null||!value.isObject())throw new IOException("技能设置格式无效");
            Map<String,Boolean> result=new HashMap<>();
            var fields=value.fields();
            while(fields.hasNext()) {
                var field=fields.next();
                if(field.getKey().isBlank()||!field.getValue().isBoolean())throw new IOException("技能设置格式无效");
                result.put(field.getKey().toLowerCase(Locale.ROOT),field.getValue().booleanValue());
            }
            return result;
        }catch(IOException failure){throw new UncheckedIOException("无法读取技能开关，原文件已保留："+file,failure);}
    }
    static synchronized void write(Path file,String name,boolean enabled) {
        var next=new TreeMap<>(read(file));next.put(name.toLowerCase(Locale.ROOT),enabled);
        try {
            Path directory=file.toAbsolutePath().getParent();Files.createDirectories(directory);
            Path temporary=Files.createTempFile(directory,".skills-",".tmp");
            try {
                try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));}
                catch(UnsupportedOperationException ignored){}
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),next);
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(temporary);}
        }catch(IOException failure){throw new UncheckedIOException("无法保存技能开关，请检查设置目录是否可写",failure);}
    }
}
