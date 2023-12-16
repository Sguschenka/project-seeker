package ru.homyakin.seeker.game.duel;

import io.vavr.control.Either;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;
import ru.homyakin.seeker.game.battle.BattlePersonage;
import ru.homyakin.seeker.game.battle.two_team.TwoPersonageTeamsBattle;
import ru.homyakin.seeker.game.duel.models.DuelError;
import ru.homyakin.seeker.game.duel.models.Duel;
import ru.homyakin.seeker.game.duel.models.DuelLocked;
import ru.homyakin.seeker.game.duel.models.DuelResult;
import ru.homyakin.seeker.game.duel.models.DuelStatus;
import ru.homyakin.seeker.game.personage.PersonageService;
import ru.homyakin.seeker.game.models.Money;
import ru.homyakin.seeker.game.personage.models.Personage;
import ru.homyakin.seeker.infrastructure.lock.LockPrefixes;
import ru.homyakin.seeker.infrastructure.lock.LockService;
import ru.homyakin.seeker.utils.models.Success;

@Component
public class DuelService {
    private final DuelDao duelDao;
    private final Duration duelLifeTime;
    private final PersonageService personageService;
    private final TwoPersonageTeamsBattle twoPersonageTeamsBattle;
    private final LockService lockService;

    public DuelService(
        DuelDao duelDao,
        DuelConfig duelConfig,
        PersonageService personageService,
        TwoPersonageTeamsBattle twoPersonageTeamsBattle,
        LockService lockService
    ) {
        this.duelDao = duelDao;
        this.duelLifeTime = duelConfig.lifeTime();
        this.personageService = personageService;
        this.twoPersonageTeamsBattle = twoPersonageTeamsBattle;
        this.lockService = lockService;
    }

    //TODO прочитать про transactional
    public Either<DuelError, Duel> createDuel(
        Personage initiatingPersonage,
        Personage acceptingPersonage
    ) {
        if (duelDao.getWaitingDuelByInitiatingPersonage(initiatingPersonage.id()).isPresent()) {
            return Either.left(new DuelError.PersonageAlreadyHasDuel());
        }
        if (initiatingPersonage.money().lessThan(DUEL_PRICE)) {
            return Either.left(new DuelError.InitiatingPersonageNotEnoughMoney(DUEL_PRICE));
        }

        personageService.takeMoney(initiatingPersonage, DUEL_PRICE);

        final var id = duelDao.create(initiatingPersonage.id(), acceptingPersonage.id(), duelLifeTime);
        return Either.right(getByIdForce(id));
    }

    public Duel getByIdForce(long duelId) {
        return duelDao.getById(duelId)
            .orElseThrow(() -> new IllegalStateException("Duel " + duelId + "must exist"));
    }

    public Either<DuelLocked, Success> expireDuel(long duelId) {
        return lockService.tryLockAndExecute(
            duelLockKey(duelId),
            () -> {
                returnMoneyToInitiator(duelId);
                duelDao.updateStatus(duelId, DuelStatus.EXPIRED);
            }
        ).mapLeft(ignored -> DuelLocked.INSTANCE);
    }

    public Either<DuelLocked, Success> declineDuel(long duelId) {
        return lockService.tryLockAndExecute(
            duelLockKey(duelId),
            () -> {
                returnMoneyToInitiator(duelId);
                duelDao.updateStatus(duelId, DuelStatus.DECLINED);
            }
        ).mapLeft(ignored -> DuelLocked.INSTANCE);
    }

    public Either<DuelLocked, DuelResult> finishDuel(Duel duel) {
        return lockService.tryLockAndCalc(
            duelLockKey(duel.id()),
            () -> finishDuelLogic(duel)
        ).mapLeft(ignored -> DuelLocked.INSTANCE);
    }

    private DuelResult finishDuelLogic(Duel duel) {
        duelDao.updateStatus(duel.id(), DuelStatus.FINISHED);
        final var personage1 = personageService.getByIdForce(duel.initiatingPersonageId());
        final var personage2 = personageService.getByIdForce(duel.acceptingPersonageId());
        final var battleResult = twoPersonageTeamsBattle.battle(
            List.of(personage1.toBattlePersonage()),
            List.of(personage2.toBattlePersonage())
        );

        final BattlePersonage winner;
        final BattlePersonage loser;
        switch (battleResult.winner()) {
            case FIRST_TEAM -> {
                winner = battleResult.firstTeamResult().battlePersonages().getFirst();
                loser = battleResult.secondTeamResult().battlePersonages().getFirst();
            }
            case SECOND_TEAM -> {
                winner = battleResult.secondTeamResult().battlePersonages().getFirst();
                loser = battleResult.firstTeamResult().battlePersonages().getFirst();
            }
            default -> throw new IllegalStateException("Unexpected status");
        }
        duelDao.addWinnerIdToDuel(duel.id(), winner.personage().id());
        return new DuelResult(winner, loser);
    }

    private void returnMoneyToInitiator(long duelId) {
        final var initiatingPersonage = personageService.getByIdForce(getByIdForce(duelId).initiatingPersonageId());
        personageService.addMoney(initiatingPersonage, DUEL_PRICE);
    }

    private String duelLockKey(long duelId) {
        return LockPrefixes.DUEL.name() + "-" + duelId;
    }

    private static final Money DUEL_PRICE = new Money(5);
}
