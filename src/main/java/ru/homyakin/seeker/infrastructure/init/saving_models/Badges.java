package ru.homyakin.seeker.infrastructure.init.saving_models;

import java.util.List;
import ru.homyakin.seeker.game.personage.badge.Badge;

public record Badges(
    List<Badge> badge
) {
}