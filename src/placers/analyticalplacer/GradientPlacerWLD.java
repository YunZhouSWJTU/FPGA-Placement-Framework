package placers.analyticalplacer;

import interfaces.Logger;
import interfaces.Options;

import java.util.Random;

import visual.PlacementVisualizer;
import circuit.Circuit;

public class GradientPlacerWLD extends GradientPlacer {

    public GradientPlacerWLD(Circuit circuit, Options options, Random random, Logger logger, PlacementVisualizer visualizer) {
        super(circuit, options, random, logger, visualizer);
    }

    @Override
    protected boolean isTimingDriven() {
        return false;
    }

    @Override
    protected void updateLegal() {
        this.legalX = this.legalizer.getLegalX();
        this.legalY = this.legalizer.getLegalY();
    }

    @Override
    public String getName() {
        return "Wirelength driven gradient descent placer";
    }
}
