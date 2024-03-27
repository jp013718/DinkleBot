package DinkleBot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import CustomUnitClasses.AbstractionLayerAI;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.abstraction.pathfinding.PathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import rts.GameState;
import rts.PhysicalGameState;
import rts.Player;
import rts.PlayerAction;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

public class DinkleBot extends AbstractionLayerAI{
  private UnitTypeTable unitTypeTable;
  private Player player;
  private GameState game;
  private PhysicalGameState board;
  private List<Unit> units, _units;

  private UnitType WORKER, LIGHT, HEAVY, RANGED, BASE, BARRACKS;
  private List<Unit> bases, barracks, workers, lights, heavies, rangers;
  private List<Unit> _bases, _barracks, _workers, _lights, _heavies, _rangers;
  private List<Unit> resources;

  @Override
  public PlayerAction getAction(int player, GameState game) {
    setActionState(player, game);
    new Bases();
    new Barracks();
    new Workers();
    new Lights();
    new Heavies();
    new Rangers();

    return translateActions(player, game);
  }

  private class Bases {
    public Bases() {
      bases.forEach(base -> {
      if (base.isIdle(game))
        assignTask(base);
      });
    }

    private void assignTask(Unit base) {
      /*
       * Decide if we need workers to be trained as builders:
       * 1. There is not one barracks for each base
       * 2. The number of builders is less than twice the number of needed barracks
       * 3. The base does not already have a barracks nearby
       */
      boolean needBuilders = barracks.size() == 0 && builders.size() == 0;
      if (!needBuilders && bases.size() != 0)
        needBuilders = barracks.size() != bases.size() && builders.size() < (bases.size()-barracks.size()) && distance(findClosest(barracks, base), base) > 2;
      /*
       * Decide if we need workers to be trained as harvesters
       * 1. The number of harvesters is less than twice the number of resource nodes on the half of the board closest to our base
       */
      boolean needHarvesters = harvesters.size() < 2*findUnitsWithin(resources, base, (int) Math.sqrt(board.getHeight()*board.getHeight() + board.getWidth()*board.getWidth())/2).size();

      if ((needBuilders || needHarvesters) && player.getResources() >= WORKER.cost) {
        train(base, WORKER);
      }
    }
  }

  private class Barracks {
    public Barracks() {
      barracks.forEach(barrack -> {
        if (barrack.isIdle(game)) {
          assignTask(barrack);
        }
      });
    }

    private void assignTask(Unit barrack) {
      // To determine if defenders should be made, we need to determine if we have enough already and if
      // the defenders we have are assigned to the nearest base
      Unit base = findClosest(bases, barrack);
      // If our base is gone, train the best unit we can and rush
      if (base == null){
        if(player.getResources() >= RANGED.cost) {
          train(barrack, RANGED);
          return;
        } else if(player.getResources() >= HEAVY.cost) {
          train(barrack, HEAVY);
          return;
        } else if(player.getResources() >= LIGHT.cost) {
          train(barrack, LIGHT);
          return;
        } else {
          return;
        }
      }
      List<Unit> nearbyDefenders = findUnitsWithin(lights, base, 4);
      boolean shouldMakeDefenders = defenders.size() < 5*bases.size() && nearbyDefenders.size() < 5;
      
      // Prioritize making light defenders. Otherwise, make the largest unit we can
      if (shouldMakeDefenders && player.getResources() >= LIGHT.cost) {
        train(barrack, LIGHT);
        return;
      } else if(player.getResources() >= RANGED.cost) {
        train(barrack, RANGED);
        return;
      } else if(player.getResources() >= HEAVY.cost) {
        train(barrack, HEAVY);
        return;
      } else if(player.getResources() >= LIGHT.cost) {
        train(barrack, LIGHT);
        return;
      }
    }
  }

  List<Unit> harvesters = new ArrayList<>();
  List<Unit> builders = new ArrayList<>();
  private class Workers{
    public Workers() {
      // Remove any workers that have died from their respective lists
      harvesters.removeIf(harvester -> !workers.contains(harvester));
      builders.removeIf(builder -> !workers.contains(builder));

      // If we have a barracks for each base, we don't need builders anymore
      if (barracks.size() == bases.size())
        builders.clear();
      // If our base is gone, we can't keep harvesting
      if (bases.size() == 0) {
        harvesters.clear();
      }

      workers.forEach(worker -> {
        if (worker.isIdle(game)) {
          assignTask(worker);
        }
      });
    }

    private void assignTask(Unit worker) {
      // Create a copy of the resources list to search for resources that are not
      // at full occupancy
      Unit resource = null;
      List<Unit> possResources = new ArrayList<>(resources);
      if (possResources.size() > 0)
        possResources.removeIf(rsrc -> findUnitsWithin(units, rsrc, 1).size()>=2);
        resource = findClosest(possResources, worker);
      
      Unit base = findClosest(bases, worker);
      Unit enemy = findClosest(_units, worker);

      if (enemy == null)
        return;
      if (base == null) {
        attack(worker, enemy);
        return;
      }
      if (resource == null) {
        attack(worker, enemy);
        return;
      }

      boolean isBuilder = builders.contains(worker);
      boolean isHarvester = harvesters.contains(worker);

      boolean needHarvesters = harvesters.size() < 2*findUnitsWithin(resources, base, (int) Math.sqrt(board.getHeight()*board.getHeight() + board.getWidth()*board.getWidth())/2).size();
      boolean canBuildBarracks = player.getResources() >= BARRACKS.cost;
      boolean needBuilders = barracks.size() == 0 && builders.size() == 0;
      if (!needBuilders)
        needBuilders = barracks.size() != bases.size() && builders.size() < (bases.size()-barracks.size()) && distance(findClosest(barracks, base), base) > 2;
      
      // Prioritize needing harvesters
      if (!isBuilder && !isHarvester && needHarvesters) {
        harvesters.add(worker);
        isHarvester = true;
        // System.out.println("Worker assigned as harvester");
      } 
      // Otherwise, we need builders
      else if (!isBuilder && !isHarvester && needBuilders && canBuildBarracks) {
        builders.add(worker);
        isBuilder = true;
        // System.out.println("Worker assigned as builder");
      } 
      // If we have an extra worker for some reason (possibly late game), instruct it to rush
      else if(!isBuilder && !isHarvester){
        attack(worker, enemy);
        // System.out.println("Worker assigned to attack");
        return;
      }

      if (isHarvester) {
        harvest(worker, resource, base);
        return;
      } else if (isBuilder) {
        /*
         * The current intent is for a builder to make a barracks close to the base, on
         * the side closest to the enemy, like so:
         * +---+---+---+---+---+          +---+---+---+---+---+
         * |   |   | B |   |   |          | B |   |   |   |   |
         * +---+---+---+---+---+          +---+---+---+---+---+
         * |   |   | b |   |   |          |   | b |   |   |   |
         * +---+---+---+---+---+          +---+---+---+---+---+
         * |   |   |   |   |   |          |   |   |   |   |   |
         * +---+---+---+---+---+          +---+---+---+---+---+
         * |   |   |   |   |   |          |   |   |   |   |   |
         * +---+---+---+---+---+          +---+---+---+---+---+
         * |   |   |_B |   |   |          |   |   |   |   |_B |
         * +---+---+---+---+---+          +---+---+---+---+---+
         * In most maps, this should avoid the barracks getting in the way of nearby
         * resources, causing the AI to stall
         */
        
        int[] barracksPosition = findBarracksLocation(base);
        // Build a barracks at the chosen space
        build(worker, BARRACKS, barracksPosition[0], barracksPosition[1]);
      }
    }

    int[] findBarracksLocation(Unit base) {
      if (base == null) {
        int[] position = {0, 0};
        return position;
      }
      
      // Get the location of the nearby base
      int baseX = base.getX();
      int baseY = base.getY();

      // Lists to hold possible (x, y) positions for the barracks
      List<Integer> possX = new ArrayList<>();
      List<Integer> possY = new ArrayList<>();

      // Determine all spaces adjacent to the base
      for (int i = -1; i <= 1; i++) {
        int x = baseX + i;
        int y = baseY + i;
        if (x >= 0 && x < board.getWidth())
          possX.add(x);
        if (y >= 0 && y < board.getHeight())
          possY.add(y);
      }

      // Make (x, y) pairs of all spaces adjacent to the base
      List<int[]> positions = new ArrayList<>();
      for (int x : possX) {
        for (int y : possY) {
          int[] position = {x, y};
          positions.add(position);
        }
      }

      // Find the nearest enemy base
      Unit _base = findClosest(_bases, base);
      int _baseX = 0;
      int _baseY = 0;
      if (_base != null) {
        _baseX = _base.getX();
        _baseY = _base.getY();
      }
      // Initialize the target build space
      int target_x = 0;
      int target_y = 0;
      int dist = board.getHeight() + board.getWidth();
      // Find the (x, y) pair that is furthest from the enemy base
      for (int[] position : positions) {
        int newDist = Math.abs(position[0] - _baseX) + Math.abs(position[1] - _baseY);
        if (newDist < dist) {
          target_x = position[0];
          target_y = position[1];
          dist = newDist;
        }
      }

      int[] target_position = {target_x, target_y};

      return target_position;
    }
  }

  private List<Unit> defenders = new ArrayList<>();
  private List<Unit> attackers = new ArrayList<>();
  private class Lights {
    public Lights() {
      // Remove any units that have died from the list of available units
      defenders.removeIf(defender -> !lights.contains(defender));
      attackers.removeIf(attacker -> !lights.contains(attacker));

      // If we've lost our base, we no longer need to defend it, and if the enemy has
      // lost their base or all other units, we'll rush
      boolean allEnemiesGone = _workers.size() == 0 && _lights.size() == 0 && _heavies.size() == 0 && _rangers.size() == 0;
      if (bases.size() == 0 || _bases.size() == 0 || allEnemiesGone) {
        defenders.clear();
      }

      lights.forEach(light -> {
        if (light.isIdle(game)) {
          assignTask(light);
        }
      });
    }

    private void assignTask(Unit light) {
      Unit base = findClosest(bases, light);
      Unit _base = findClosest(_bases, light);
      Unit enemy = findClosest(_units, light);
      List<Unit> nearbyDefenders = new ArrayList<>();
      boolean isDefender = defenders.contains(light);
      boolean isAttacker = attackers.contains(light);      

      if (enemy == null)
        return;
      if (base != null) {
        nearbyDefenders = findUnitsWithin(lights, base, 4);
      } else if (base == null) {
        attack(light, enemy);
        return;
      }

      /* 
       * Determine whether we need defenders based on three conditions:
       * 1. If at least one base has less than 5 defenders (Maybe unnecessary, but serves as sanity check)
       * 2. If the nearest base has less than 5 defenders
       * 3. If we have a base to defend
       * If all of these conditions are met and the light unit is unassigned, it will be assigned as a defender
       */
      boolean allEnemiesGone = _workers.size() == 0 && _lights.size() == 0 && _heavies.size() == 0 && _rangers.size() == 0;
      boolean needDefenders = defenders.size() < 5*bases.size() && nearbyDefenders.size() < 5 && base != null && !allEnemiesGone;
      if (needDefenders && !isAttacker && !isDefender) {
        defenders.add(light);
        isDefender = true;
      } else if (!isAttacker && !isDefender) {
        attackers.add(light);
        isAttacker = true;
      }

      /* 
       * Defender Behavior:
       * Defenders are planned to create a phalanx between the nearest base and
       * the enemy base, in a fashion like so:
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * | B |   |   |   | L |   |          |   |   |   | B |   |   |   |
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * |   |   |   | L |   |   |          |   |   |   |   |   |   |   |
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * |   |   | L |   |   |   |          |   | L |   |   |   | L |   |
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * |   | L |   |   |   |   |          |   |   | L |   | L |   |   |
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * | L |   |   |   |   |   |          |   |   |   | L |   |   |   |
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * |   |   |   |   |   |_B |          |   |   |   |_B |   |   |   |
       * +---+---+---+---+---+---+          +---+---+---+---+---+---+---+
       * If an enemy gets within a given distance of the base the unit is defending, the
       * units will choose to attack the enemy and then return to the defensive position
       */
      if (isDefender) {
        // Logic to determine if a defender should attack a nearby enemy
        if (findUnitsWithin(_units, base, 8).size() > 0) {
          Unit target = findClosest(_units, base);
          attack(light, target);
          return;
        } else {
          // Determining the exact space the unit should occupy...
          // Get the location of the nearest base
          int baseX = base.getX();
          int baseY = base.getY();

          // Get all x and y within four moves of the base
          List<Integer> possX = new ArrayList<>();
          List<Integer> possY = new ArrayList<>();
          for (int i = -4; i <= 4; i++) {
            int x = baseX + i;
            int y = baseY + i;
            if (x >= 0 && x < board.getWidth()) {
              possX.add(x);
            }
            if (y >= 0 && y < board.getHeight()) {
              possY.add(y);
            }
          }

          // Collect all positions that are exactly four moves from the base
          List<int[]> positions = new ArrayList<>();
          for (int x : possX) {
            for (int y : possY) {
              if (Math.abs(x - baseX) + Math.abs(y - baseY) == 4) {
                int[] position = {x, y};
                positions.add(position);
              }
            }
          }

          // Get the location of the closest enemy base
          int _baseX = _base.getX();
          int _baseY = _base.getY();

          // Find the unoccupied position closest to the enemy base
          int target_x = 0;
          int target_y = 0;
          int dist = board.getHeight() + board.getWidth();
          for (int[] pair : positions) {
            int newDist = Math.abs(pair[0]-_baseX) + Math.abs(pair[1]-_baseY);
            if (pair[0] == light.getX() && pair[1] == light.getY())
              return;
            if (newDist < dist && board.getUnitAt(pair[0], pair[1]) == null) {
              target_x = pair[0];
              target_y = pair[1];
              dist = newDist;
            }
          }

          // Move to the chosen position
          move(light, target_x, target_y);
          return;
        }
      } else {
        attack(light, enemy);
        return;
      }
    }
  }

  private class Heavies {
    public Heavies() {
      heavies.forEach(heavy -> {
        if (heavy.isIdle(game)) {
          assignTask(heavy);
        }
      });
    }

    private void assignTask(Unit heavy) {
      attack(heavy, findClosest(_units, heavy));
    }
  }

  private class Rangers {
    public Rangers() {
      rangers.forEach(ranger -> {
        if (ranger.isIdle(game)) {
          assignTask(ranger);
        }
      });
    }

    private void assignTask(Unit ranger) {
      attack(ranger, findClosest(_units, ranger));
    }
  }

  private Unit findClosest(List<Unit> units, Unit reference) {
    return units.stream().min(Comparator.comparingInt(u -> distance(u, reference))).orElse(null);
  }

  private List<Unit> findUnitsWithin(List<Unit> units, Unit reference, int distance) {
    return units.stream().filter(u -> distance(u, reference) <= distance).collect(Collectors.toList());
  }

  private int distance(Unit u1, Unit u2) {
    return Math.abs(u1.getX() - u2.getX()) + Math.abs(u1.getY() - u2.getY());
  }

  public DinkleBot(UnitTypeTable unitTypeTable) {
    this(unitTypeTable, new AStarPathFinding());
  }

  public DinkleBot(UnitTypeTable unitTypeTable, PathFinding pf) {
    super(pf);
    this.unitTypeTable = unitTypeTable;
    setUnitTypes();
    resetUnits();
  }

  @Override
  public void reset(UnitTypeTable unitTypeTable) {
    super.reset(unitTypeTable);
  }

  @Override
  public AI clone() {
    return new DinkleBot(unitTypeTable, pf);
  }

  public void setActionState(int player, GameState game) {
    this.player = game.getPlayer(player);
    this.game = game;
    board = game.getPhysicalGameState();

    resetUnits();
    for (Unit unit : board.getUnits()) {
      if (unit.getPlayer() == player) {
        units.add(unit);
        switch (unit.getType().name) {
          case "Base":
            bases.add(unit);
            break;
          case "Barracks":
            barracks.add(unit);
            break;
          case "Worker":
            workers.add(unit);
            break;
          case "Light":
            lights.add(unit);
            break;
          case "Heavy":
            heavies.add(unit);
            break;
          case "Ranged":
            rangers.add(unit);
            break;
        } 
      } else if (unit.getPlayer() >= 0) {
        _units.add(unit);
        switch(unit.getType().name) {
          case "Base":
            _bases.add(unit);
            break;
          case "Barracks":
            _barracks.add(unit);
            break;
          case "Worker":
            _workers.add(unit);
            break;
          case "Light":
            _lights.add(unit);
            break;
          case "Heavy":
            _heavies.add(unit);
            break;
          case "Ranged":
            _rangers.add(unit);
            break;
        }
      } else {
        resources.add(unit);
      }
    }
  }

  private void setUnitTypes() {
    for (UnitType unitType : unitTypeTable.getUnitTypes()) {
      switch(unitType.name) {
        case "Worker":
          WORKER = unitType;
          break;
        case "Light":
          LIGHT = unitType;
          break;
        case "Heavy":
          HEAVY = unitType;
          break;
        case "Ranged":
          RANGED = unitType;
          break;
        case "Base":
          BASE = unitType;
          break;
        case "Barracks":
          BARRACKS = unitType;
          break;
      }
    }
  }

  private void resetUnits() {
    units = new ArrayList<>();
    _units = new ArrayList<>();
    bases = new ArrayList<>();
    _bases = new ArrayList<>();
    barracks = new ArrayList<>();
    _barracks = new ArrayList<>();
    workers = new ArrayList<>();
    _workers = new ArrayList<>();
    lights = new ArrayList<>();
    _lights = new ArrayList<>();
    heavies = new ArrayList<>();
    _heavies = new ArrayList<>();
    rangers = new ArrayList<>();
    _rangers = new ArrayList<>();
    resources = new ArrayList<>();
  }

  @Override
  public List<ParameterSpecification> getParameters() {
    return null;
  }
}

