package codes.castled.allium.hide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HidePolicyTest {
    HidePolicy policy(boolean white,List<String> commands,List<String> tabs){return new HidePolicy(List.of(new HidePolicy.Definition("default",white,commands,tabs,List.of(),List.of())));}
    @ParameterizedTest @CsvSource({"party invite Steve,true","allium:party invite Steve,true","/PARTY  invite Steve,true","party kick,false","party invited,false","party,false"})
    void executionUsesWholeBranches(String path,boolean allowed){var p=policy(true,List.of("party invite"),List.of());assertEquals(allowed,HidePolicy.allows(p.select(List.of()),path,false));}
    @ParameterizedTest @CsvSource({"party,true","party admin,true","party admin invite Steve,true","party admin kick,false","party leave,false"})
    void completionWhitelistIncludesRequiredAncestors(String path,boolean allowed){var p=policy(true,List.of(),List.of("party admin invite"));assertEquals(allowed,HidePolicy.allows(p.select(List.of()),path,true));}
    @Test void executionAndSuggestionsAreIndependent(){var p=policy(true,List.of("warp"),List.of("spawn"));assertTrue(HidePolicy.allows(p.select(List.of()),"warp",false));assertFalse(HidePolicy.allows(p.select(List.of()),"warp",true));assertTrue(HidePolicy.allows(p.select(List.of()),"spawn",true));assertFalse(HidePolicy.allows(p.select(List.of()),"spawn",false));}
    @Test void caretUsesResolvedCommands(){var p=policy(true,List.of("msg"),List.of("^"));assertTrue(HidePolicy.allows(p.select(List.of()),"msg Steve",true));}
    @Test void blacklistWinsOverWhitelist(){var p=new HidePolicy(List.of(new HidePolicy.Definition("default",true,List.of("party"),List.of("party"),List.of(),List.of()),new HidePolicy.Definition("alt",false,List.of("party kick"),List.of("party invite"),List.of(),List.of())));assertFalse(HidePolicy.allows(p.select(List.of("default","alt")),"party kick Steve",false));assertTrue(HidePolicy.allows(p.select(List.of("default","alt")),"party",true));assertFalse(HidePolicy.allows(p.select(List.of("default","alt")),"party invite",true));}
    @Test void namespaceWildcardsRespectBoundaries(){assertTrue(HidePolicy.matches(List.of("essentials:*"),"essentials:spawn",false));assertFalse(HidePolicy.matches(List.of("essentials:*"),"other:spawn",false));}
    @Test void scopedInheritanceRemainsIndependent(){var p=new HidePolicy(List.of(new HidePolicy.Definition("base",true,List.of("spawn"),List.of("mail"),List.of(),List.of()),new HidePolicy.Definition("staff",true,List.of("ban"),List.of("kick"),List.of("base"),List.of())));assertTrue(HidePolicy.allows(p.select(List.of("staff")),"spawn",false));assertFalse(HidePolicy.allows(p.select(List.of("staff")),"mail",true));}
    @Test void inheritanceCyclesAndUnknownParentsAreRejected(){assertThrows(IllegalArgumentException.class,()->new HidePolicy(List.of(new HidePolicy.Definition("a",true,List.of(),List.of(),List.of("b"),List.of()),new HidePolicy.Definition("b",true,List.of(),List.of(),List.of("a"),List.of()))));assertThrows(IllegalArgumentException.class,()->new HidePolicy(List.of(new HidePolicy.Definition("a",true,List.of(),List.of(),List.of("missing"),List.of()))));}
    @Test void requestReplacementOffsetReconstructsFullPaths(){assertEquals("party invite steve",HidePolicy.suggestionPath("/party invite St","Steve",14));assertEquals("party invite",HidePolicy.suggestionPath("/party i","invite",7));}
    @Test void blacklistInheritedWhitelistConstrainsUnlistedBranches(){var p=new HidePolicy(List.of(new HidePolicy.Definition("base",true,List.of("party invite","party leave"),List.of(),List.of(),List.of()),new HidePolicy.Definition("alt",false,List.of("party invite"),List.of(),List.of("base"),List.of())));assertFalse(HidePolicy.allows(p.select(List.of("alt")),"party invite Steve",false));assertTrue(HidePolicy.allows(p.select(List.of("alt")),"party leave",false));assertFalse(HidePolicy.allows(p.select(List.of("alt")),"party kick",false));}
}
