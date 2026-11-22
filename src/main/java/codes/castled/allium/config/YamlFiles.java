package codes.castled.allium.config;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.*;
/** Block-style serialization with a one-time original and atomic replacement. */
public final class YamlFiles {
 private YamlFiles(){}
 public static void normalize(Path file,YamlConfiguration yaml)throws IOException{
  String text=text(file,yaml);if(text.equals(Files.readString(file)))return;
  Path backup=file.resolveSibling(file.getFileName()+".before-format");if(!Files.exists(backup))Files.copy(file,backup,StandardCopyOption.COPY_ATTRIBUTES);
  write(file,text);
 }
 /** Replaces {@code file} without a {@code .before-format} copy, for callers that kept their own backup. */
 public static void replace(Path file,YamlConfiguration yaml)throws IOException{
  String text=text(file,yaml);if(!Files.exists(file)||!text.equals(Files.readString(file)))write(file,text);
 }
 private static String text(Path file,YamlConfiguration yaml){
  String text=yaml.saveToString();if(file.getFileName().toString().equals("config.yml")&&yaml.contains("sign-use-per-second"))text=SignConfigComments.format(text,yaml.getStringList("enabledSigns"));return text;
 }
 private static void write(Path file,String text)throws IOException{
  Path temp=Files.createTempFile(file.getParent(),".allium-yaml-",".tmp");
  try{Files.writeString(temp,text);try{if(Files.exists(file))Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(file));}catch(UnsupportedOperationException ignored){}Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
 }
}
