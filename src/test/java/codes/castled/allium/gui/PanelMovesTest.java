package codes.castled.allium.gui;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class PanelMovesTest {
 static ItemStack stack(Material type,int amount){
  int[] count={amount};ItemStack s=mock(ItemStack.class);
  when(s.getType()).thenReturn(type);when(s.getAmount()).thenAnswer(i->count[0]);when(s.getMaxStackSize()).thenReturn(64);
  doAnswer(i->{count[0]=i.getArgument(0);return null;}).when(s).setAmount(anyInt());
  when(s.isSimilar(any())).thenAnswer(i->i.getArgument(0) instanceof ItemStack o&&o.getType()==type);
  when(s.clone()).thenAnswer(i->stack(type,count[0]));return s;
 }
 static Inventory panel(ItemStack[] cells){
  Inventory inv=mock(Inventory.class);when(inv.getMaxStackSize()).thenReturn(64);
  when(inv.getItem(anyInt())).thenAnswer(i->cells[i.<Integer>getArgument(0)]);
  doAnswer(i->{cells[i.<Integer>getArgument(0)]=i.getArgument(1);return null;}).when(inv).setItem(anyInt(),any());return inv;
 }
 @Test void fillsSimilarStacksThenEmptyCellsButNeverTheSkippedCell(){
  var cells=new ItemStack[54];for(int i=0;i<29;i++)cells[i]=stack(Material.DIRT,64);cells[5]=stack(Material.STONE,60);
  int left=PanelMoves.moveInto(panel(cells),36,29,stack(Material.STONE,70));
  assertEquals(0,left);assertEquals(64,cells[5].getAmount(),"similar stack first");
  assertNull(cells[29],"the held hotbar cell is skipped");assertEquals(64,cells[30].getAmount());assertEquals(2,cells[31].getAmount());
 }
 @Test void returnsWhatDoesNotFit(){
  var cells=new ItemStack[54];for(int i=0;i<36;i++)if(i!=29)cells[i]=stack(Material.DIRT,64);
  assertEquals(10,PanelMoves.moveInto(panel(cells),36,29,stack(Material.STONE,10)));assertNull(cells[29]);
 }
}
