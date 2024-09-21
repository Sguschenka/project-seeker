package ru.homyakin.seeker.telegram.event;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class EventScheduler {
    private final TgEventLauncher eventLauncher;
    private final TgEventStopper eventStopper;

    public EventScheduler(TgEventLauncher eventLauncher, TgEventStopper eventStopper) {
        this.eventLauncher = eventLauncher;
        this.eventStopper = eventStopper;
    }

    // Если шедулер запускается моментально, то приложение не успевает инициализировать локализацию
    // пока забагфикшено кроном, но это выглядит плохим решением
    @Scheduled(cron = "0 * * * * *")
    public void scheduledEventsLaunch() {
        eventLauncher.launchRaidsInGroups();
    }

    @Scheduled(cron = "0 * * * * *")
    public void scheduledStopEvents() {
        eventStopper.stopEvents();
    }
}