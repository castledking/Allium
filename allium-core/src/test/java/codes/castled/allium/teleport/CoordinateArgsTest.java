package codes.castled.allium.teleport;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
class CoordinateArgsTest {
 @Test void absoluteRelativeAndCentered(){
  var p=CoordinateArgs.parse(List.of("100","64","-20"),1,2,3,10,20);
  assertEquals(100.5,p.x());assertEquals(64,p.y());assertEquals(-19.5,p.z(),"whole numbers are block centers, as vanilla /tp");
  assertEquals(10f,p.yaw());assertEquals(20f,p.pitch());assertEquals(Optional.empty(),p.world());
  var r=CoordinateArgs.parse(List.of("~","~5","~-2.5","90","-45","world_nether"),10.25,64,3,0,0);
  assertEquals(10.25,r.x());assertEquals(69,r.y());assertEquals(0.5,r.z());assertEquals(90f,r.yaw());assertEquals(-45f,r.pitch());
  assertEquals(Optional.of("world_nether"),r.world());
  assertEquals(12.25,CoordinateArgs.parse(List.of("12.25","0","0"),0,0,0,0,0).x(),"decimals are exact");
  assertEquals(-170f,CoordinateArgs.parse(List.of("0","0","0","190"),0,0,0,0,0).yaw(),"yaw wraps like vanilla");
  assertEquals(15f,CoordinateArgs.parse(List.of("0","0","0","~5"),0,0,0,10,0).yaw());
 }
 @Test void recognisesThePositionForm(){
  assertTrue(CoordinateArgs.startsWithPosition(List.of("1","~","-3.5")));
  assertFalse(CoordinateArgs.startsWithPosition(List.of("Steve","~","1")));
  assertFalse(CoordinateArgs.startsWithPosition(List.of("100","64")),"two numbers are not a position");
  assertFalse(CoordinateArgs.isCoordinate("~x"));assertFalse(CoordinateArgs.isCoordinate("1e9"));assertFalse(CoordinateArgs.isCoordinate("NaN"));
 }
 @Test void refusesBadInput(){
  assertThrows(IllegalArgumentException.class,()->CoordinateArgs.parse(List.of("0","0"),0,0,0,0,0));
  assertThrows(IllegalArgumentException.class,()->CoordinateArgs.parse(List.of("0","0","0","0","91"),0,0,0,0,0));
  assertThrows(IllegalArgumentException.class,()->CoordinateArgs.parse(List.of("40000000","0","0"),0,0,0,0,0));
  assertThrows(IllegalArgumentException.class,()->CoordinateArgs.parse(List.of("0","0","0","a"),0,0,0,0,0));
  assertThrows(IllegalArgumentException.class,()->CoordinateArgs.parse(List.of("0","0","0","0","0","w","extra"),0,0,0,0,0));
 }
}
