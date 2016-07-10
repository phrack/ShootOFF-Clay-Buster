package com.shootoff.plugins;

import java.io.InputStream;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.shootoff.camera.Shot;
import com.shootoff.gui.LocatedImage;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;
import com.shootoff.util.NamedThreadFactory;

import javafx.geometry.Point2D;
import javafx.scene.transform.Rotate;

public class ClayBuster extends ProjectorTrainingExerciseBase implements TrainingExercise {
	private static final int CLAY_LAUNCH_DELAY = 3; /* seconds */
	private static final String SHOTGUN_RANGE_BACKGROUND = "background/shotgun_range.gif";
	private static final int BACKGROUND_WIDTH = 1920;
	private static final int BACKGROUND_HEIGHT = 1436;
	private static final int UNSCALED_BUNKER_X = 750;
	private static final int UNSCALED_BUNKER_Y = 560;

	private static ProjectorTrainingExerciseBase thisSuper;

	private int scaledBunkerX = 0;
	private int scaledBunkerY = 0;

	private int hitClays = 0;
	private int missedClays = 0;
	private int shots = 0;
	private final Set<Clay> visibleClays = new HashSet<Clay>();

	private static final int CORE_POOL_SIZE = 6;
	private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(CORE_POOL_SIZE,
			new NamedThreadFactory("ClayBusterExercise"));

	public ClayBuster() {
	}

	public ClayBuster(List<Target> targets) {
		super(targets);
		setThisSuper(super.getInstance());
	}

	private static void setThisSuper(ProjectorTrainingExerciseBase thisSuper) {
		ClayBuster.thisSuper = thisSuper;
	}

	@Override
	public void init() {
		InputStream is = this.getClass().getClassLoader().getResourceAsStream(SHOTGUN_RANGE_BACKGROUND);
		LocatedImage img = new LocatedImage(is, SHOTGUN_RANGE_BACKGROUND);
		super.setArenaBackground(img);

		final double scaleX = super.getArenaWidth() / BACKGROUND_WIDTH;
		scaledBunkerX = (int) ((double) UNSCALED_BUNKER_X * scaleX);

		final double scaleY = super.getArenaHeight() / BACKGROUND_HEIGHT;
		scaledBunkerY = (int) ((double) UNSCALED_BUNKER_Y * scaleY);

		super.showTextOnFeed("Broken Clays: 0\nMissed Clays: 0\nShots: 0");

		executorService.schedule(() -> launchClay(), CLAY_LAUNCH_DELAY, TimeUnit.SECONDS);
		executorService.schedule(() -> moveClays(), 100, TimeUnit.MILLISECONDS);
	}

	public void launchClay() {
		visibleClays.add(new Clay(scaledBunkerX, scaledBunkerY));
		executorService.schedule(() -> launchClay(), CLAY_LAUNCH_DELAY, TimeUnit.SECONDS);
	}

	public void moveClays() {
		Set<Clay> removableClays = new HashSet<Clay>();

		for (Clay clay : visibleClays) {
			if (!clay.moveTarget()) {
				removableClays.add(clay);
				missedClays++;
			}
		}

		visibleClays.removeAll(removableClays);

		for (Clay clay : removableClays) {
			super.removeTarget(clay.getTarget());
		}

		if (!removableClays.isEmpty()) {
			super.showTextOnFeed(
					String.format("Broken Clays: %d%nMissed Clays: %d%nShots: %d", hitClays, missedClays, shots));
		}

		executorService.schedule(() -> moveClays(), 100, TimeUnit.MILLISECONDS);
	}

	private enum Direction {
		LEFT, RIGHT
	}

	private static class Clay {
		private final Direction direction;
		private double angle;
		private final int dy;
		private final int dx;
		private final Target target;
		private final int defaultTargetWidth;
		private final int defaultTargetHeight;
		private int targetDistance;

		private static final int MIN_CLAY_VELOCITY_X = 3;
		private static final int MAX_CLAY_VELOCITY_X = 7;
		private static final int MIN_CLAY_VELOCITY_Y = 5;
		private static final int MAX_CLAY_VELOCITY_Y = 20;

		private static final int MIN_CLAY_WIDTH = 10;

		public Clay(int bunkerX, int bunkerY) {
			Random rng = new Random();

			if (rng.nextBoolean()) {
				direction = Direction.LEFT;
			} else {
				direction = Direction.RIGHT;
			}

			dy = rng.nextInt((MAX_CLAY_VELOCITY_Y - MIN_CLAY_VELOCITY_Y) + 1) + MIN_CLAY_VELOCITY_Y;

			final File targetFile;
			if (Direction.LEFT.equals(direction)) {
				targetFile = new File("@clays/Clay_left.target");
				dx = (rng.nextInt((MAX_CLAY_VELOCITY_X - MIN_CLAY_VELOCITY_X) + 1) + MIN_CLAY_VELOCITY_X) * -1;
				angle = -10;
			} else {
				targetFile = new File("@clays/Clay_right.target");
				dx = rng.nextInt((MAX_CLAY_VELOCITY_X - MIN_CLAY_VELOCITY_X) + 1) + MIN_CLAY_VELOCITY_X;
				angle = 10;
			}

			Optional<Target> newTarget = thisSuper.addTarget(targetFile, (double) bunkerX, (double) bunkerY);

			if (newTarget.isPresent()) {
				target = newTarget.get();
				defaultTargetWidth = Integer.parseInt(target.getTag(Target.TAG_DEFAULT_PERCEIVED_WIDTH));
				defaultTargetHeight = Integer.parseInt(target.getTag(Target.TAG_DEFAULT_PERCEIVED_HEIGHT));
				targetDistance = Integer.parseInt(target.getTag(Target.TAG_DEFAULT_PERCEIVED_DISTANCE));
			} else {
				throw new AssertionError("The clay target " + targetFile.getPath() + " does not exist.");
			}
		}

		public Target getTarget() {
			return target;
		}

		/**
		 * Move the clay across the sky.
		 * 
		 * @return <code>true</code> if the clay isn't too far away or off
		 *         screen yet
		 */
		public boolean moveTarget() {
			if (Direction.RIGHT.equals(direction)) {
				angle += 1;
			} else {
				angle -= 1;
			}

			Point2D p = target.getPosition();

			Rotate r = new Rotate(angle, p.getX(), p.getY());
			Point2D rotatedPoint = r.transform(p.getX() + dx, p.getY() - dy);

			target.setPosition(rotatedPoint.getX(), rotatedPoint.getY());

			if (thisSuper.isPerspectiveInitialized()) {
				targetDistance += dy * 10;

				thisSuper.setTargetDistance(target, defaultTargetWidth, defaultTargetHeight, targetDistance);
			}

			// Return false if went off screen or got too small
			p = target.getPosition();
			
			return p.getX() + target.getDimension().getWidth() > 0 && p.getX() < thisSuper.getArenaWidth()
					&& p.getY() + target.getDimension().getHeight() > 0
					&& target.getDimension().getWidth() > MIN_CLAY_WIDTH;
		}
	}

	@Override
	public ExerciseMetadata getInfo() {
		return new ExerciseMetadata("Clay Buster", "1.0", "phrack",
				String.format("This exercise randomly launches a clay left or right at "
						+ "slightly varying velocities every %d seconds.", CLAY_LAUNCH_DELAY));
	}

	@Override
	public void shotListener(Shot shot, Optional<Hit> hit) {
		if (hit.isPresent()) {
			Target clayTarget = hit.get().getTarget();
			Optional<Clay> shotClay = Optional.empty();

			for (Clay clay : visibleClays) {
				if (clay.getTarget().equals(clayTarget)) {
					shotClay = Optional.of(clay);
					break;
				}
			}

			if (shotClay.isPresent()) {
				visibleClays.remove(shotClay.get());
				hitClays++;

				// Let animation play then remove the target
				executorService.schedule(() -> super.removeTarget(clayTarget), 500, TimeUnit.MILLISECONDS);
			}
		}

		shots++;

		super.showTextOnFeed(
				String.format("Broken Clays: %d%nMissed Clays: %d%nShots: %d", hitClays, missedClays, shots));
	}

	@Override
	public void reset(List<Target> targets) {
		hitClays = 0;
		missedClays = 0;
		shots = 0;

		for (Clay clay : visibleClays) {
			super.removeTarget(clay.getTarget());
		}

		visibleClays.clear();

		super.showTextOnFeed("Broken Clays: 0\nMissed Clays: 0\nShots: 0");
		
		executorService.schedule(() -> launchClay(), CLAY_LAUNCH_DELAY, TimeUnit.SECONDS);
	}

	@Override
	public void destroy() {
		executorService.shutdownNow();
		super.destroy();
	}
}
