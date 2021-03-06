import enums.Direction;
import enums.FCA_Schemes;

import java.util.*;

public class Simulator {
    public final int totalEventCount = 10000;
    public final int warmUpPeriod = 1705;
    private PriorityQueue<Event> eventQueue;
    public double clock;
    public BaseStation[] baseStations;
    public int blockedCallCount;
    public int droppedCallCount;

    private final Integer COLUMN_ARRIVAL_TIME = 1;
    private final Integer COLUMN_BASE_STATION = 2;
    private final Integer COLUMN_CALL_DURATION = 3;
    private final Integer COLUMN_CAR_SPEED = 4;

    private int eventCount; // counter to keep track of the warm-up period

    // comparator to make event with earlier event time to be dequeued first
    private static Comparator<Event> eventComparator = new Comparator<Event>() {
        @Override
        public int compare(Event e1, Event e2) {
            if (e1.getEventTime() < e2.getEventTime())
                return -1;
            if (e1.getEventTime() > e2.getEventTime())
                return 1;
            else
                return 0;
        }
    };

    public void init(FCA_Schemes scheme) {
        // initialize the variables
        eventQueue = new PriorityQueue<>(1, eventComparator);
        clock = 0;
        baseStations = new BaseStation[20];
        blockedCallCount = 0;
        droppedCallCount = 0;
        eventCount = 0;

        for (int i = 0; i < 20; ++i) {
            baseStations[i] = new BaseStation(i);
            if (scheme == FCA_Schemes.NINE_FREE_CHANNELS_ONE_RESERVED) {
                baseStations[i].setNumFreeChannels(9);
                baseStations[i].setNumReservedChannel(1);
            }
        }
    }

    public void beginSimulation(Boolean isStochastic, FCA_Schemes scheme) {
        //reading input from Random Number Generator or given model input data
        if (isStochastic) {
            RandomNumberGenerator rng = new RandomNumberGenerator();

            for (int i = 0; i < totalEventCount; i++) {
                double iat = rng.getCarInterArrivalTimes();
                CallInitiationEvent event = new CallInitiationEvent(
                        i + 1,
                        clock + iat,
                        baseStations[rng.getBaseStation() - 1],
                        rng.getCarSpeed(),
                        rng.getCallDuration(),
                        rng.getRandomDirection(),
                        rng.getPositionInBaseStation()
                );
                clock = clock + iat;
                eventQueue.add(event);
            }
        } else {
            FileReader reader = null;
            try {
                reader = new FileReader(
//                    "/Users/sengwee/Desktop/Simulation-of-a-Cellular-Telephony-Network/source code/PCS_TEST_DETERMINSTIC_1819S2.csv");
                        "C:\\Users\\Seng Wee\\Documents\\Google Drive\\NTU\\Course Materials\\Y3S2\\CZ4015 SIMULATION & MODELLING\\assignments\\1\\submission\\source code\\PCS_TEST_DETERMINSTIC_1819S2.csv");
            } catch (Exception e) {
                System.out.println("Error reading the input file: " + e);
            }
            // make the input reader read off the first header row
            String[] headerRow = reader.readOneRow();

            RandomNumberGenerator rng = new RandomNumberGenerator();
            for (int i = 0; i < totalEventCount; ++i) {
                String[] row = reader.readOneRow();

                // randomize the direction of the travelling car
                Direction direction = rng.getRandomDirection();

                // randomize the position of the car in the base station
                Double position = Math.random() * 2000.0;

                CallInitiationEvent event = new CallInitiationEvent(
                        Integer.parseInt(row[0]),
                        Double.parseDouble(row[COLUMN_ARRIVAL_TIME]),
                        baseStations[Integer.parseInt(row[COLUMN_BASE_STATION]) - 1],
                        Double.parseDouble(row[COLUMN_CAR_SPEED]),
                        Double.parseDouble(row[COLUMN_CALL_DURATION]),
                        direction,
                        position
                );
                eventQueue.add(event);

                handleEvent(scheme);
            }
        }

        while (!eventQueue.isEmpty()) {
            handleEvent(scheme);
        }
    }

    private void handleEvent(FCA_Schemes scheme) {
        Event event = eventQueue.peek();
        eventQueue.remove(event);

        this.clock = event.getEventTime(); // advance simulation clock
        BaseStation currentStation = event.getBaseStation();

        if (event instanceof CallInitiationEvent) {
            if (currentStation.getNumFreeChannels() > 0) {
                currentStation.useOneChannel();
                generateNextEvent(event);
            } else {
                blockedCallCount++;
            }
            eventCount++;
            if (eventCount == warmUpPeriod) {
                blockedCallCount = 0;
                droppedCallCount = 0;
            }

        } else if (event instanceof CallHandoverEvent) {
            // get the next Base Station
            BaseStation nextBaseStation;
            if (event.getDirection() == Direction.TO_STATION_ONE) {
                nextBaseStation = baseStations[event.getBaseStation().getId() - 1];
            } else {
                nextBaseStation = baseStations[event.getBaseStation().getId() + 1];
            }

            if (scheme == FCA_Schemes.NINE_FREE_CHANNELS_ONE_RESERVED && currentStation.getNumReservedChannel() == 0) {
                currentStation.releaseUsedReservedChannel();
            } else {
                currentStation.releaseUsedChannel();
            }

            event.setBaseStation(nextBaseStation);

            if (nextBaseStation.getNumFreeChannels() > 0) {
                nextBaseStation.useOneChannel();
                generateNextEvent(event);

                // under the reserved channel for handover FCA Scheme, the specified channel is used for handing over call
            } else if (scheme == FCA_Schemes.NINE_FREE_CHANNELS_ONE_RESERVED && currentStation.getNumReservedChannel() > 0) {
                nextBaseStation.useOneReservedChannel();
                generateNextEvent(event);

            } else {
                droppedCallCount++;
            }
        } else if (event instanceof CallTerminationEvent) {
            currentStation.releaseUsedChannel();
        }
    }

    private void generateNextEvent(Event event) {
        Event nextEvent;
        Double remainingDistance;

        // Remaining distance for the car to travel to the next Base Station
        // is calculated by subtracting the car's current position
        // from the maximum base station coverage radius which is 2000m.

        if (event instanceof CallInitiationEvent) {
            if (event.getDirection() == Direction.TO_STATION_ONE) {
                remainingDistance = event.getPosition();
            } else {
                // car
                remainingDistance = 2000.0 - event.getPosition();
            }
        } else {
            // calculate time to reach the next base station
            remainingDistance = 2000.0 - event.getPosition();
        }

        // convert km/h to m/s
        double speedInMetersPerSecond = event.getSpeed() * 1000.0 / 3600.0;
        double remainingTime = Math.min(remainingDistance / speedInMetersPerSecond, event.getCallDuration());
        // calculate the new event callDuration
        double newEventDuration = event.getCallDuration() - remainingTime;
        if (
                event.getCallDuration() > remainingTime &&
                        event.getDirection() == Direction.TO_STATION_TWENTY &&
                        event.getBaseStation().getId() != 19
        ) {
            // instantiate a new instance of Call Handover Event
            // pass the current event attributes to the next event
            // 1. event ID
            // 2. new event time
            // 3. speed of current car
            // 4. next Base Station
            // 5. updated call callDuration after passing this Base Station
            // 6. direction that the car is moving towards
            nextEvent = new CallHandoverEvent(
                    event.getId(),
                    clock + remainingTime,
                    event.getBaseStation(),
                    event.getSpeed(),
                    newEventDuration,
                    event.getDirection(),
                    0.0
            );
        } else if (
                event.getCallDuration() > remainingTime &&
                        event.getDirection() == Direction.TO_STATION_ONE &&
                        event.getBaseStation().getId() != 0
        ) {
            nextEvent = new CallHandoverEvent(
                    event.id,
                    clock + remainingTime,
                    event.getBaseStation(),
                    event.getSpeed(),
                    newEventDuration,
                    event.getDirection(),
                    0.0
            );
        } else {
            // call ended before reaching next base station
            nextEvent = new CallTerminationEvent(
                    event.getId(),
                    clock + remainingTime,
                    event.getBaseStation()
            );
        }
        eventQueue.add(nextEvent);
    }

    public void printStatistics() {
        System.out.println("Blocked Call Count: " + blockedCallCount);
        System.out.print("Blocked Call Percentage: ");
        System.out.printf("%.2f", (double) blockedCallCount / (totalEventCount - warmUpPeriod) * 100);
        System.out.print("%");
        System.out.println();
        System.out.println("Dropped Call Count: " + droppedCallCount);
        System.out.print("Dropped Call Percentage:");
        System.out.printf("%.2f", (double) droppedCallCount / (totalEventCount - warmUpPeriod) * 100);
        System.out.print("%");
        System.out.println();
    }

}