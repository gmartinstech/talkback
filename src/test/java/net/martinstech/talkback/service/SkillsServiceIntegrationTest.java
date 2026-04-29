package net.martinstech.talkback.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SkillsServiceIntegrationTest {

    @Test
    public void testNpxAvailable() {
        SkillsService svc = new SkillsService();
        assertTrue(svc.isNpxAvailable(), "npx skills CLI should be available");
    }

    @Test
    public void testListInstalledSkills() {
        SkillsService svc = new SkillsService();
        var skills = svc.listInstalledSkills();
        assertFalse(skills.isEmpty(), "Should have installed skills after vercel-labs install");

        boolean hasVercel = skills.stream()
            .anyMatch(s -> s.id().contains("vercel") || s.name().contains("vercel"));
        assertTrue(hasVercel, "Should have vercel skills installed");
    }

    @Test
    public void testGetSkillDetails() {
        SkillsService svc = new SkillsService();
        var skills = svc.listInstalledSkills();
        assertFalse(skills.isEmpty(), "Should have skills to get details for");

        String firstId = skills.get(0).id();
        var details = svc.getSkillDetails(firstId);
        assertNotNull(details, "Should be able to read skill details for " + firstId);
        assertFalse(details.name().isBlank(), "Skill name should not be blank");
        assertFalse(details.content().isBlank(), "Skill content should not be blank");
    }

    @Test
    public void testSkillSearch() {
        SkillsService svc = new SkillsService();
        var results = svc.findSkills("react");
        // Results may be empty if offline, so just assert no exception
        assertNotNull(results, "Search should return a list, even if empty");
    }
}
