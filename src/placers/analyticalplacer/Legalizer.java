package placers.analyticalplacer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import circuit.Circuit;
import circuit.architecture.BlockType;
import circuit.architecture.BlockCategory;
import circuit.block.AbstractSite;
import circuit.block.GlobalBlock;
import circuit.exceptions.FullSiteException;
import circuit.exceptions.InvalidBlockException;
import circuit.exceptions.PlacedBlockException;
import circuit.exceptions.UnplacedBlockException;

public class Legalizer {

    private static enum Axis {X, Y};

    private Circuit circuit;
    private CostCalculator costCalculator;
    private int width, height;

    private Map<GlobalBlock, Integer> blockIndexes;
    private List<BlockType> blockTypes;
    private List<Integer> blockTypeIndexStarts;
    private int numBlocks, numIOBlocks, numMovableBlocks;

    private double tileCapacity;

    private double[] linearX;
    private double[] linearY;
    private int[] bestLegalX;
    private int[] bestLegalY;
    private int[] tmpLegalX;
    private int[] tmpLegalY;

    // Contain the properties of the blockType that is currently being legalized
    private BlockType blockType;
    private int blockHeight, blockRepeat;

    // These are temporary data structures
    private LegalizerArea[][] areaPointers;
    private List<List<List<Integer>>> blockMatrix;

    private double bestCost;
    boolean firstOneDone;


    Legalizer(
            Circuit circuit,
            CostCalculator costCalculator,
            Map<GlobalBlock, Integer> blockIndexes,
            List<BlockType> blockTypes,
            List<Integer> blockTypeIndexStarts,
            double[] linearX,
            double[] linearY) throws IllegalArgumentException {

        // Store easy stuff
        this.circuit = circuit;
        this.width = this.circuit.getWidth();
        this.height = this.circuit.getHeight();

        this.blockIndexes = blockIndexes;
        this.costCalculator = costCalculator;


        // Store block types
        if(blockTypes.get(0).getCategory() != BlockCategory.IO) {
            throw new IllegalArgumentException("The first block type is not IO");
        }
        if(blockTypes.size() != blockTypeIndexStarts.size() - 1) {
            throw new IllegalArgumentException("The objects blockTypes and blockTypeIndexStarts don't have matching dimensions");
        }

        this.blockTypes = blockTypes;
        this.blockTypeIndexStarts = blockTypeIndexStarts;

        // Store linear solution (this array is updated by the linear solver
        this.linearX = linearX;
        this.linearY = linearY;

        // Cache the number of blocks
        this.numBlocks = linearX.length;
        this.numIOBlocks = blockTypeIndexStarts.get(1);
        this.numMovableBlocks = this.numBlocks - this.numIOBlocks;

        // Initialize the best solution and a temporary solution
        this.bestLegalX = new int[this.numBlocks];
        this.bestLegalY = new int[this.numBlocks];
        this.tmpLegalX = new int[this.numBlocks];
        this.tmpLegalY = new int[this.numBlocks];
        this.bestCost = Double.MAX_VALUE;

        // Initialize the IO positions in tmpLegal and bestLegal
        for(int i = 0; i < this.numIOBlocks; i++) {
            this.bestLegalX[i] = (int) this.linearX[i];
            this.bestLegalY[i] = (int) this.linearY[i];
        }
        System.arraycopy(this.bestLegalX, 0, this.tmpLegalX, 0, this.numBlocks);
        System.arraycopy(this.bestLegalY, 0, this.tmpLegalY, 0, this.numBlocks);
    }


    double getBestCost() {
        return this.bestCost;
    }
    int[] getBestLegalX() {
        return this.bestLegalX;
    }
    int[] getBestLegalY() {
        return this.bestLegalY;
    }

    int[] getAnchorsX() {
        return this.tmpLegalX;
    }
    int[] getAnchorsY() {
        return this.tmpLegalY;
    }


    void legalize(double tileCapacity, boolean legalizeIOBlocks) throws UnplacedBlockException, InvalidBlockException, PlacedBlockException, FullSiteException {
        this.tileCapacity = tileCapacity;

        if(legalizeIOBlocks) {
            legalizeIOBlocks();
        }

        // Skip i = 0: these are IO blocks
        for(int i = 1; i < this.blockTypes.size(); i++) {
            legalizeBlockType(i);
        }

        this.updateBestLegal();
    }


    void legalizeIOBlocks() {
        int blockTypeIndex = 0;
        this.blockType = this.blockTypes.get(blockTypeIndex);
        initializeBlockMatrix(blockTypeIndex);


        int numSites = 2 * (this.width + this.height - 4);
        double blocksPerSite = this.numIOBlocks / (double) numSites;

        int placedBlocks = 0;
        int legalX = 1, legalY = 0;
        int optimalX = legalX, optimalY = legalY;

        List<Integer> currentBlockList = this.blockMatrix.get(optimalX).get(optimalY);
        int indexInCurrentBlockList = 0;

        for(int siteIndex = 0; siteIndex < numSites; siteIndex++) {
            while(placedBlocks < blocksPerSite * (siteIndex + 1)) {
                while(currentBlockList.size() == indexInCurrentBlockList) {
                    optimalX = nextX(optimalX, optimalY);
                    optimalY = nextY(optimalX, optimalY);

                    currentBlockList = this.blockMatrix.get(optimalX).get(optimalY);
                    indexInCurrentBlockList = 0;
                }

                int blockIndex = currentBlockList.get(indexInCurrentBlockList);
                this.tmpLegalX[blockIndex] = legalX;
                this.tmpLegalY[blockIndex] = legalY;
                placedBlocks++;
            }

            legalX = nextX(legalX, legalY);
            legalY = nextY(legalX, legalY);
        }
    }

    private int nextX(int currentX, int currentY) {
        return currentX + (currentX < this.width-1 && currentY <= 1 ?  1 : currentX > 0 && currentY >= this.height-2 ? -1 : 0);
    }
    private int nextY(int currentX, int currentY) {
        return currentY + (currentX == 0 ? -1 : currentX == this.width -1 ?  1 : 0);
    }


    void legalizeBlockType(int blockTypeIndex) {
        this.blockType = this.blockTypes.get(blockTypeIndex);
        this.blockHeight = this.blockType.getHeight();
        this.blockRepeat = this.blockType.getRepeat();

        // Make a matrix that contains the blocks that are closest to each position
        initializeBlockMatrix(blockTypeIndex);


        // Build a set of disjunct areas that are not over-utilized
        this.areaPointers = new LegalizerArea[this.width][this.height];
        List<LegalizerArea> areas = new ArrayList<LegalizerArea>();

        int xCenter = this.width / 2;
        int yCenter = this.height / 2;
        int maxDimension = Math.max(xCenter, yCenter);

        this.tryNewArea(areas, xCenter, yCenter);
        for(int centerDist1 = 1; centerDist1 < maxDimension; centerDist1++) {
            for(int centerDist2 = -centerDist1; centerDist2 < centerDist1; centerDist2++) {
                this.tryNewArea(areas, xCenter + centerDist1, yCenter + centerDist2);
                this.tryNewArea(areas, xCenter - centerDist1, yCenter - centerDist2);
                this.tryNewArea(areas, xCenter + centerDist2, yCenter - centerDist1);
                this.tryNewArea(areas, xCenter - centerDist2, yCenter + centerDist1);
            }
        }

        // Legalize all unabsorbed areas
        for(LegalizerArea area : areas) {
            if(!area.isAbsorbed()) {
                this.legalizeArea(area);
            }
        }
    }

    private void initializeBlockMatrix(int blockTypeIndex) {

        // Initialize the matrix to contain a linked list at each coordinate
        this.blockMatrix = new ArrayList<List<List<Integer>>>(this.width);
        for(int x = 0; x < this.width; x++) {
            List<List<Integer>> blockColumn = new ArrayList<List<Integer>>(this.height);
            for(int y = 0; y < this.height; y++) {
                blockColumn.add(new ArrayList<Integer>());
            }
            this.blockMatrix.add(blockColumn);
        }


        // Loop through all the blocks of the correct block type and add them to their closest position
        int startIndex = this.blockTypeIndexStarts.get(blockTypeIndex);
        int endIndex = this.blockTypeIndexStarts.get(blockTypeIndex + 1);

        for(int index = startIndex; index < endIndex; index++) {
            AbstractSite site = this.getClosestSite(this.linearX[index], this.linearY[index]);
            int x = site.getX();
            int y = site.getY();

            this.blockMatrix.get(x).get(y).add(index);
        }
    }


    private AbstractSite getClosestSite(double x, double y) {

        switch(this.blockType.getCategory()) {
            case IO:
                int siteX, siteY;
                if(x > y) {
                    if(x > this.height - y - 1) {
                        // Right quadrant
                        siteX = this.width - 1;
                        siteY = (int) Math.max(Math.min(Math.round(y), this.height - 2), 1);

                    } else {
                        // Top quadrant
                        siteX = (int)  Math.max(Math.min(Math.round(x), this.height - 2), 1);
                        siteY = 0;
                    }

                } else {
                    if(x > this.height - y - 1) {
                        //Bottom quadrant
                        siteX = (int)  Math.max(Math.min(Math.round(x), this.height - 2), 1);
                        siteY = this.height - 1;

                    } else {
                        // Left quadrant
                        siteX = 0;
                        siteY = (int) Math.max(Math.min(Math.round(y), this.height - 2), 1);
                    }
                }

                return this.circuit.getSite(siteX, siteY);

            case CLB:
                int row = (int) Math.round(Math.max(Math.min(y, this.height - 2), 1));

                // Get closest column
                // Not easy to do this with calculations if there are multiple hardblock types
                // So just trial and error
                int column = (int) Math.round(x);
                int step = 1;
                int direction = (x > column) ? 1 : -1;

                while(true) {
                    if(column > 0 && column < this.width-1 && this.circuit.getColumnType(column).equals(this.blockType)) {
                        break;
                    }

                    column += direction * step;
                    step++;
                    direction *= -1;
                }

                return this.circuit.getSite(column, row);


            // Hardblocks
            default:
                int start = this.blockType.getStart();
                int repeat = this.blockType.getRepeat();
                int blockHeight = this.blockType.getHeight();

                int numRows = (int) Math.floor((this.height - 2) / blockHeight);
                int numColumns = (int) Math.floor((this.width - start - 2) / repeat + 1);

                int columnIndex = (int) Math.round(Math.max(Math.min((x - start) / repeat, numColumns - 1), 0));
                int rowIndex = (int) Math.round(Math.max(Math.min((y - 1) / blockHeight, numRows - 1), 0));

                return this.circuit.getSite(columnIndex * repeat + start, rowIndex * blockHeight + 1);
        }
    }


    private void tryNewArea(List<LegalizerArea> areas, int x, int y) {
        if(x > 0 && x < this.width - 1
                && y > 0 && y < this.height - 1
                && this.blockMatrix.get(x).get(y).size() >= 1
                && this.areaPointers[x][y] == null) {
            LegalizerArea newArea = this.newArea(x, y);
            areas.add(newArea);
        }
    }

    private LegalizerArea newArea(int x, int y) {

        // left, top, right, bottom
        LegalizerArea area = new LegalizerArea(x, y, this.tileCapacity, this.blockType);
        area.incrementTiles();
        area.addBlockIndexes(this.blockMatrix.get(x).get(y));

        int directionIndex = 0;
        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

        // status = 0: original direction
        // status = 1: opposite direction (when growing in original direction is not possible anymore)
        // status = 2: no direction (when growing in original or opposite direction is not possible)
        int[] directionStatuses = {0, 0, 0, 0};
        int impossibleDirections = 0;

        while(area.getOccupation() > area.getCapacity() && impossibleDirections < 4) {

            if(directionStatuses[directionIndex] == 0 && !growthPossible(area, directions[directionIndex])) {
                directions[directionIndex][0] = -directions[directionIndex][0];
                directions[directionIndex][1] = -directions[directionIndex][1];
                directionStatuses[directionIndex] = 1;
            }

            if(directionStatuses[directionIndex] == 1 && !growthPossible(area, directions[directionIndex])) {
                directionStatuses[directionIndex] = 2;
                impossibleDirections++;
            }

            if(directionStatuses[directionIndex] != 2) {
                // goalArea is the area that area should eventually cover
                LegalizerArea goalArea = new LegalizerArea(area);
                goalArea.grow(directions[directionIndex]);
                this.growArea(area, goalArea);
            }

            directionIndex = (directionIndex + 1) % 4;
        }

        return area;
    }

    private boolean growthPossible(LegalizerArea area, int[] direction) {
        if(direction[0] == 0) {
            if(direction[1] == 1) {
                return area.bottom + 2 * this.blockHeight <= this.height - 1;
            } else {
                return area.top - this.blockHeight >= 1;
            }

        } else {
            if(direction[0] == 1) {
                return area.right + this.blockRepeat <= this.width - 2;
            } else {
                return area.left - this.blockRepeat >= 1;
            }
        }
    }


    private void growArea(LegalizerArea area, LegalizerArea goalArea) {

        int[] rows = {0, 0};
        int[] columns = {0, 0};

        // While goalArea is not completely covered by area
        while(true) {
            int[] direction = {0, 0};

            if(area.left != goalArea.left) {
                rows[0] = area.top;
                rows[1] = area.bottom;
                columns[0] = area.left - this.blockRepeat;
                columns[1] = area.left - this.blockRepeat;
                area.grow(-1, 0);

            } else if(area.right != goalArea.right) {
                rows[0] = area.top;
                rows[1] = area.bottom;
                columns[0] = area.right + this.blockRepeat;
                columns[1] = area.right + this.blockRepeat;
                area.grow(1, 0);

            } else if(area.top != goalArea.top) {
                rows[0] = area.top - this.blockHeight;
                rows[1] = area.top - this.blockHeight;
                columns[0] = area.left;
                columns[1] = area.right;
                area.grow(0, -1);

            } else if(area.bottom != goalArea.bottom) {
                rows[0] = area.bottom + this.blockHeight;
                rows[1] = area.bottom + this.blockHeight;
                columns[0] = area.left;
                columns[1] = area.right;
                area.grow(0, 1);

            } else {
                return;
            }

            area.grow(direction);


            for(int y = rows[0]; y <= rows[1]; y += this.blockHeight) {
                for(int x = columns[0]; x <= columns[1]; x += this.blockRepeat) {

                    // If this tile is occupied by an unabsorbed area
                    LegalizerArea neighbour = this.areaPointers[x][y];
                    if(neighbour != null && !neighbour.isAbsorbed()) {
                        neighbour.absorb();

                        // Update the goal area to contain the absorbed area
                        goalArea.left = Math.min(goalArea.left, neighbour.left);
                        goalArea.right = Math.max(goalArea.right, neighbour.right);
                        goalArea.top = Math.min(goalArea.top, neighbour.top);
                        goalArea.bottom = Math.max(goalArea.bottom, neighbour.bottom);
                    }

                    // Update the area pointer
                    this.areaPointers[x][y] = area;

                    // Add the blocks to the area
                    area.addBlockIndexes(this.blockMatrix.get(x).get(y));

                    // Update the capacity
                    AbstractSite site = this.circuit.getSite(x, y, true);
                    if(site != null && site.getType().equals(this.blockType)) {
                        area.incrementTiles();
                    }
                }
            }
        }
    }



    private void legalizeArea(LegalizerArea area) {
        List<Integer> blockIndexes = area.getBlockIndexes();
        int[] coordinates = {area.left, area.top, area.right, area.bottom};
        this.legalizeArea(coordinates, blockIndexes, Axis.X);
    }

    private void legalizeArea(
            int[] coordinates,
            List<Integer> blockIndexes,
            Axis axis) {

        // If the area is only one tile big: place all the blocks on this tile
        if(coordinates[2] - coordinates[0] < this.blockRepeat && coordinates[3] - coordinates[1] < this.blockHeight) {

            for(Integer blockIndex : blockIndexes) {
                this.tmpLegalX[blockIndex] = coordinates[0];
                this.tmpLegalY[blockIndex] = coordinates[1];
            }

            return;

        } else if(blockIndexes.size() == 0) {
            return;

        } else if(blockIndexes.size() == 1) {
            int blockIndex = blockIndexes.get(0);
            double linearX = this.linearX[blockIndex];
            double linearY = this.linearY[blockIndex];

            double minDistance = Double.MAX_VALUE;
            int minX = -1, minY = -1;

            for(int x = coordinates[0]; x <= coordinates[2]; x += this.blockRepeat) {
                if(!this.circuit.getColumnType(x).equals(this.blockType)) {
                    continue;
                }

                for(int y = coordinates[1]; y <= coordinates[3]; y += this.blockHeight) {
                    double distance = Math.pow(linearX - x, 2) + Math.pow(linearY - y, 2);
                    if(distance < minDistance) {
                        minDistance = distance;
                        minX = x;
                        minY = y;
                    }
                }
            }

            this.tmpLegalX[blockIndex] = minX;
            this.tmpLegalY[blockIndex] = minY;
            return;

        } else if(coordinates[2] - coordinates[0] < this.blockRepeat && axis == Axis.X) {
            this.legalizeArea(coordinates, blockIndexes, Axis.Y);
            return;

        } else if(coordinates[3] - coordinates[1] < this.blockHeight && axis == Axis.Y) {
            this.legalizeArea(coordinates, blockIndexes, Axis.X);
            return;
        }

        // Split area along axis and store ratio between the two subareas
        // Sort blocks along axis
        int[] coordinates1 = new int[4], coordinates2 = new int[4];
        System.arraycopy(coordinates, 0, coordinates1, 0, 4);
        System.arraycopy(coordinates, 0, coordinates2, 0, 4);

        double splitRatio;
        Axis newAxis;

        if(axis == Axis.X) {

            // If the blockType is CLB
            if(this.blockType.getCategory() == BlockCategory.CLB) {
                int numClbColumns = 0;
                for(int column = coordinates[0]; column <= coordinates[2]; column++) {
                    if(this.circuit.getColumnType(column).getCategory() == BlockCategory.CLB) {
                        numClbColumns++;
                    }
                }

                int splitColumn = -1;
                int halfNumClbColumns = 0;
                for(int column = coordinates[0]; column <= coordinates[2]; column++) {
                    if(this.circuit.getColumnType(column).getCategory() == BlockCategory.CLB) {
                        halfNumClbColumns++;
                    }

                    if(halfNumClbColumns >= numClbColumns / 2) {
                        splitColumn = column;
                        break;
                    }
                }

                splitRatio = halfNumClbColumns / (double) numClbColumns;

                coordinates1[2] = splitColumn;
                coordinates2[0] = splitColumn + 1;

            // Else: it's a hardblock
            } else {
                int numColumns = (coordinates[2] - coordinates[0]) / this.blockRepeat + 1;
                splitRatio = (numColumns / 2) / (double) numColumns;

                coordinates1[2] = coordinates[0] + (numColumns / 2 - 1) * this.blockRepeat;
                coordinates2[0] = coordinates[0] + (numColumns / 2) * this.blockRepeat;
            }

            Collections.sort(blockIndexes, new BlockComparator(this.linearX));

            newAxis = Axis.Y;

        } else {

            // If the blockType is CLB
            if(this.blockRepeat == 1) {
                int splitRow = (coordinates[1] + coordinates[3]) / 2;
                splitRatio = (splitRow - coordinates[1] + 1) / (double) (coordinates[3] - coordinates[1] + 1);

                coordinates1[3] = splitRow;
                coordinates2[1] = splitRow + 1;

            // Else: it's a hardblock
            } else {
                int numRows = (coordinates[3] - coordinates[1]) / this.blockHeight + 1;
                splitRatio = (numRows / 2) / (double) numRows;

                coordinates1[3] = coordinates[1] + (numRows / 2 - 1) * this.blockHeight;
                coordinates2[1] = coordinates[1] + (numRows / 2) * this.blockHeight;
            }

            Collections.sort(blockIndexes, new BlockComparator(this.linearY));

            newAxis = Axis.X;
        }


        // Split blocks in two lists with a ratio approx. equal to area split
        int split = (int) Math.ceil(splitRatio * blockIndexes.size());
        List<Integer> blocks1 = new ArrayList<>(blockIndexes.subList(0, split));
        List<Integer> blocks2 = new ArrayList<>(blockIndexes.subList(split, blockIndexes.size()));

        this.legalizeArea(coordinates1, blocks1, newAxis);
        this.legalizeArea(coordinates2, blocks2, newAxis);
    }


    private void updateBestLegal() throws UnplacedBlockException, InvalidBlockException, PlacedBlockException, FullSiteException {
        boolean update = this.costCalculator.requiresCircuitUpdate();

        if(update) {
            this.updateCircuit(this.tmpLegalX, this.tmpLegalY);
        }

        double newCost = this.costCalculator.calculate(this.tmpLegalX, this.tmpLegalY);

        if(newCost < this.bestCost && this.tileCapacity <= 1) {
            System.arraycopy(this.tmpLegalX, this.numIOBlocks, this.bestLegalX, this.numIOBlocks, this.numMovableBlocks);
            System.arraycopy(this.tmpLegalY, this.numIOBlocks, this.bestLegalY, this.numIOBlocks, this.numMovableBlocks);
            this.bestCost = newCost;

        } else if(update) {
            this.updateCircuit();
        }
    }

    void updateCircuit() throws UnplacedBlockException, InvalidBlockException, PlacedBlockException, FullSiteException {
        this.updateCircuit(this.bestLegalX, this.bestLegalY);
    }
    void updateCircuit(int[] x, int[] y) throws UnplacedBlockException, InvalidBlockException, PlacedBlockException, FullSiteException {
        //Clear all previous locations
        for(GlobalBlock block : this.blockIndexes.keySet()) {
            if(block.getCategory() != BlockCategory.IO) {
                block.removeSite();
            }
        }

        // Update locations
        for(Map.Entry<GlobalBlock, Integer> blockEntry : this.blockIndexes.entrySet()) {
            GlobalBlock block = blockEntry.getKey();

            if(block.getCategory() != BlockCategory.IO) {
                int index = blockEntry.getValue();

                AbstractSite site = this.circuit.getSite(x[index], y[index], true);
                block.setSite(site);
            }
        }
    }
}