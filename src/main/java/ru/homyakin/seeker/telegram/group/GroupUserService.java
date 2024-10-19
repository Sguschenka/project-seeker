package ru.homyakin.seeker.telegram.group;

import io.vavr.control.Either;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import ru.homyakin.seeker.telegram.TelegramSender;
import ru.homyakin.seeker.telegram.group.models.GroupTg;
import ru.homyakin.seeker.telegram.group.models.GroupTgId;
import ru.homyakin.seeker.telegram.group.stats.GroupPersonageStatsService;
import ru.homyakin.seeker.telegram.models.ChatMemberError;
import ru.homyakin.seeker.telegram.user.models.UserId;
import ru.homyakin.seeker.telegram.utils.TelegramMethods;
import ru.homyakin.seeker.utils.models.Pair;
import ru.homyakin.seeker.telegram.group.database.GroupUserDao;
import ru.homyakin.seeker.telegram.group.models.GroupUser;
import ru.homyakin.seeker.telegram.user.UserService;
import ru.homyakin.seeker.telegram.user.models.User;

@Service
public class GroupUserService {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final GroupTgService groupTgService;
    private final UserService userService;
    private final GroupUserDao groupUserDao;
    private final TelegramSender telegramSender;
    private final GroupPersonageStatsService groupPersonageStatsService;

    public GroupUserService(
        GroupTgService groupTgService,
        UserService userService,
        GroupUserDao groupUserDao,
        TelegramSender telegramSender,
        GroupPersonageStatsService groupPersonageStatsService
    ) {
        this.groupTgService = groupTgService;
        this.userService = userService;
        this.groupUserDao = groupUserDao;
        this.telegramSender = telegramSender;
        this.groupPersonageStatsService = groupPersonageStatsService;
    }

    public boolean isUserAdminInGroup(GroupTgId groupId, UserId userId) {
        return telegramSender.send(TelegramMethods.createGetChatMember(groupId, userId))
            .fold(
                _ -> false,
                it -> it instanceof ChatMemberAdministrator || it instanceof ChatMemberOwner
            );
    }

    public Pair<GroupTg, User> getAndActivateOrCreate(GroupTgId groupId, UserId userId) {
        final var group = groupTgService.getOrCreate(groupId);
        final var user = userService.getOrCreateFromGroup(userId);
        groupUserDao.getByGroupIdAndUserId(groupId, userId)
            .ifPresentOrElse(
                groupUser -> groupUser.activate(groupUserDao),
                () -> createGroupUser(group, user)
            );
        return Pair.of(group, user);
    }

    public Optional<User> getRandomUserFromGroup(GroupTgId groupId) {
        return groupUserDao.getRandomUserByGroup(groupId)
            .map(groupUser -> userService.getOrCreateFromGroup(groupUser.userId()));
    }

    public Either<ChatMemberError.InternalError, Boolean> isUserStillInGroup(GroupTgId groupId, UserId userId) {
        final var result =  telegramSender.send(TelegramMethods.createGetChatMember(groupId, userId));
        if (result.isLeft()) {
            return switch (result.getLeft()) {
                case ChatMemberError.UserNotFound _, ChatMemberError.InvalidParticipant _ -> {
                    logger.warn("User {} is no longer in group {}", userId.value(), groupId.value());
                    deactivateGroupUser(groupId, userId);
                    yield Either.right(false);
                }
                case ChatMemberError.InternalError internalError -> Either.left(internalError);
            };
        }
        return Either.right(true);
    }

    public int countUsersInGroup(GroupTgId groupId) {
        return groupUserDao.countUsersInGroup(groupId);
    }

    private void deactivateGroupUser(GroupTgId groupId, UserId userId) {
        groupUserDao.getByGroupIdAndUserId(groupId, userId)
            .map(groupUser -> groupUser.deactivate(groupUserDao));
    }

    private void createGroupUser(GroupTg group, User user) {
        groupUserDao.save(new GroupUser(group.id(), user.id(), true));
        groupPersonageStatsService.create(group.id(), user.personageId());
    }
}
