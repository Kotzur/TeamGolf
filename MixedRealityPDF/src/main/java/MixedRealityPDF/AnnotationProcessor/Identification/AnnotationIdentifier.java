package MixedRealityPDF.AnnotationProcessor.Identification;

import MixedRealityPDF.AnnotationProcessor.AnnotationBoundingBox;
import MixedRealityPDF.AnnotationProcessor.Annotations.Annotation;
import MixedRealityPDF.AnnotationProcessor.Annotations.Highlight;
import MixedRealityPDF.AnnotationProcessor.Annotations.Text;
import MixedRealityPDF.AnnotationProcessor.Annotations.UnderLine;
import MixedRealityPDF.AnnotationProcessor.ClusteringPoint;
import com.sun.org.apache.xpath.internal.SourceTree;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Decides what sort of Annotation parts of document specified by bounding boxes
 * are.
 * **/
public class AnnotationIdentifier implements IAnnotationIdentifier{
    private IFeatureExtractor featureExtractor;
    private String relativePath;

    public AnnotationIdentifier(IFeatureExtractor FE){
        featureExtractor = FE;
        Path currentRelativePath = Paths.get("");
        relativePath = currentRelativePath.toAbsolutePath().toString();
        createTreeTrainingFile();
    }

    public AnnotationIdentifier(){
        featureExtractor = new FeatureExtractorV2();
        Path currentRelativePath = Paths.get("");
        relativePath = currentRelativePath.toAbsolutePath().toString();
        createTreeTrainingFile();
    }

    /**
     * Central method which is invoked by the main pipeline.
     * Crops out annotations from the full difference image of a PDF page
     * according to their bounding boxes, analyse the resulting images in a
     * Python decision tree and outputs an identified collection of Annotation
     * objects, specific to their type
     * @param fullImage image of the full PDF page with its difference taken so
     *                  that it's only full of annotations in colour
     * @param points collection of AnnotationBoundingBoxes which specify
     *               location of annotations on the page
     * @param pageNumber index of the PDF page
     * @return collection of identified annotations
     * **/
    public Collection<Annotation> identifyAnnotations(BufferedImage fullImage,
            Collection<AnnotationBoundingBox> points, int pageNumber) {
        ArrayList<BufferedImage> annImages =
                IAnnotationIdentifier.cropAnnotations(fullImage, points);
        FileWriter writer = initializeFileWriter("predictionData.csv");

        writeLineCSV(writer, new ArrayList<>(featureExtractor.getFeatureNames()));

        for (BufferedImage annotationImage : annImages) {
            saveFeaturesToCSV(annotationImage, writer, "");
        }

        closeWriter(writer);

        List<String> keys = runDecisionTree();
        return createAnnotationObjects(keys, points, annImages, pageNumber);
    }

    public Collection<Annotation> testIdentifyAnnotations(
            Collection<AnnotationBoundingBox> points, int pageNumber){
        ArrayList<BufferedImage> annotationImages = new ArrayList<>();

        // load test images
        try {
            Path dirPath = Paths.get(
                    Paths.get(relativePath, "Data", "test").toString());
            BufferedImage image;
            File[] files = new File(dirPath.toString()).listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    image = ImageIO.read(file.getAbsoluteFile());
                    annotationImages.add(image);
                }
            }
        }catch(IOException ioe){
            System.err.println("Error while loading test images.");
            ioe.printStackTrace();
        }

        //save features from test data
        FileWriter writer = initializeFileWriter("predictionData.csv");

        writeLineCSV(writer, new ArrayList<>(featureExtractor.getFeatureNames()));

        for (BufferedImage annotationImage : annotationImages) {
            saveFeaturesToCSV(annotationImage, writer, "");
        }

        closeWriter(writer);

        // identify annotations
        List<String> keys = runDecisionTree();
        ArrayList<Annotation> identifiedAnnotations;
        identifiedAnnotations = createAnnotationObjects(keys, points,
                annotationImages, pageNumber);
        int i = 0;
        for(Annotation annotation : identifiedAnnotations){
            if(annotation instanceof Text){
                System.out.println(i + ": Text");
            }
            else if(annotation instanceof Highlight){
                System.out.println(i + ": High");
            }
            else if(annotation instanceof UnderLine){
                System.out.println(i + ": Under");
            }
            i++;
        }
        return identifiedAnnotations;

    }

    /**
     * Loops through all images to create their objects depending on class
     * determined by key from python output.
     * This requires data for images to be in parallel across all data
     * structures so that iterating over them gives data corresponding to the
     * same annotation with each step: image with index i in annotationImages[i]
     * will have its bounding box at points[i] and its key at
     * decisionTreeOutput[i]
     *
     * @param keys output from decision tree in the form of strings:
     *             "highlight", "text" and "underline"
     * @param points bounding boxes for annotations, necessary to get
     *               their x-y coordinates
     * @param annotationImages annotation images, necessary to get their
     *                         dimentions
     * @param pageNumber index of PDF page
     * @return collection of annotation objects, each of specific type
     * according to their key
     * **/
    private ArrayList<Annotation> createAnnotationObjects(
            Collection<String> keys, Collection<AnnotationBoundingBox> points,
            Collection<BufferedImage> annotationImages, int pageNumber){
        ArrayList<Annotation> identifiedAnnotations = new ArrayList<>();
        Iterator<String> keyIt = keys.iterator();
        Iterator<AnnotationBoundingBox> boxIt = points.iterator();
        Iterator<BufferedImage> imageIt = annotationImages.iterator();
        int x, y, width, height;
        String key;
        AnnotationBoundingBox currentBox;
        BufferedImage annotationImage;

        System.out.println("keys: " + keys.size());
        System.out.println("points: " + points.size());
        System.out.println("images: " + annotationImages.size());

        while(keyIt.hasNext() && boxIt.hasNext() && imageIt.hasNext()){
            key = keyIt.next();
            currentBox = boxIt.next();
            annotationImage = imageIt.next();

            x = currentBox.getBottomLeft().getX();
            y = currentBox.getBottomLeft().getY();
            // Converting imgY to pdfY. - no need to convert x.
            y = Annotation.ImageYToPDFY(y, annotationImage.getHeight());
            width = annotationImage.getWidth();
            height = annotationImage.getHeight();
            switch (key) {
                case "highlight":
                    Highlight hl;
                    hl = new Highlight(x, y, width, height, pageNumber);
                    identifiedAnnotations.add(hl);
                    break;
                case "text":
                    Text text = new Text(x, y, annotationImage, pageNumber);
                    identifiedAnnotations.add(text);
                    break;
                case "underline":
                    UnderLine ul = new UnderLine(x, y, width, pageNumber);
                    identifiedAnnotations.add(ul);
                    break;
            }
        }
        return identifiedAnnotations;
    }

    /**
     * Invokes python script which holds the main decision tree mechanism by
     * calling it from Command Line.
     * **/
    private List<String> runDecisionTree(){
        String annotationKey;
        List<String> decisionTreeOutput = new ArrayList<>();
        // run python script with decision tree
        String pythonScriptPath = Paths.get(relativePath, "src", "main", "java",
                "MixedRealityPDF", "AnnotationProcessor", "Identification",
                "decision_tree.py").toString();
        try {
            // create Python output file
            Path pyOutPath = Paths.get(relativePath, "Data", "pythonOut.txt");
            new File(pyOutPath.toString()).createNewFile();
//            // start Python script
//            ProcessBuilder runPythonScript = new ProcessBuilder("python3", pythonScriptPath);
//            runPythonScript.start();

            Runtime.getRuntime().exec("python3 " + pythonScriptPath);

            try(BufferedReader br = new BufferedReader(new FileReader(pyOutPath.toString()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    decisionTreeOutput.add(line);
                }
            }

            System.out.println("number of keys: " + decisionTreeOutput.size());
            for(String key : decisionTreeOutput){
                System.out.println("key = " + key);
            }

        }catch(IOException ie){
            System.err.println(
                    "Error executing python script from command line");
            ie.printStackTrace();
        }
        return decisionTreeOutput;
    }

    /**
     * Analyse image passed to get its features and dimentions and save them all
     * into CSV format.
     * **/
    private void saveFeaturesToCSV(
            BufferedImage image, FileWriter fileWriter, String key){
        List<String> record = featureExtractor.extractFeatures(image);
        // save the key field only in case it's passed in = it's training data
        if(!key.isEmpty())
            record.add(key);
        writeLineCSV(fileWriter, record);
    }

    private FileWriter initializeFileWriter(String filePath){
        String csvPath = Paths.get(relativePath, "Data", filePath).toString();
        FileWriter writer = null;
        try {
            writer = new FileWriter(new File(csvPath));
        }catch(IOException ioe) {
            System.err.println("Error reading CSV file");
        }
        return writer;
    }

    /**
     * Creates CSV file used for training decision tree in Python.
     * This method needs to be run only once so the file is created, but the
     * decision tree itself is recreated each time the script is called.
     * The file is in MixedRealityPDF/Data directory and uses training data
     * acquired from readClassImageMap() method.
     * Names of individual features corresponding to FeatureExtractor classes
     * are written as the first line (stored in firstLine ArrayList).
     * **/
    private void createTreeTrainingFile(){
        // decided to go with the approach of passing data to Python via CSVs
        // because Jython is too complicated and slow compared with fileIO
        FileWriter writer = initializeFileWriter("trainingData.csv");

        // get the images to train with
        Map<String, ArrayList<BufferedImage>> classImageMap = null;
        try{
            classImageMap = readClassImageMap();
        }catch(IOException ioe){
            System.err.println("Error reading images for training ");
        }

        // write the first line of CSV file with column names (names of
        // features)
        ArrayList<String> firstLine = new ArrayList<>(featureExtractor.getFeatureNames());
        firstLine.add("key");
        writeLineCSV(writer, firstLine);

        // extract features from each image and save them to CSV file
        for (Map.Entry<String, ArrayList<BufferedImage>> entry
                : classImageMap.entrySet()) {
            ArrayList<BufferedImage> images = entry.getValue();
            for (BufferedImage image : images) {
                saveFeaturesToCSV(image, writer, entry.getKey());
            }
        }
        closeWriter(writer);

    }

    /**
     * Reads images in folders specified in dirNames array and puts them in a
     * map according to which folder they came from, indicating the type of
     * image they are.
     * @throws IOException if there is an error when reading images for training
     * @return HashMap from Strings which are names of classes of annotation
     * images to ArrayList<BufferedImage> which contains all images of that
     * type.
     * **/
    private Map<String, ArrayList<BufferedImage>> readClassImageMap()
            throws IOException{
        // Directory names become keys in the map
        String [] dirNames = new String[]{"text", "underline", "highlight"};
        Map<String, ArrayList<BufferedImage>> imagesInClasses = new HashMap<>();

        for(String dirName : dirNames) {
            Path dirPath = Paths.get(Paths.get(relativePath, "Data", dirName)
                    .toString());

            // load each image and add it to the output list
            ArrayList<BufferedImage> imagesList = new ArrayList<>();
            BufferedImage image;

            File[] files = new File(dirPath.toString()).listFiles();
            for (File file : files) {
                if (file.isFile()) {
                    image = ImageIO.read(file.getAbsoluteFile());
                    imagesList.add(image);
                }
            }

            imagesInClasses.put(dirName, imagesList);
        }
        return imagesInClasses;
    }

    private void closeWriter(FileWriter writer){
        try{
            writer.flush();
            writer.close();
        }catch(IOException ioe){
            System.err.println("Error closing writer");
            ioe.printStackTrace();
        }
    }

    /* -------------- CSV writing methods -------------- */
    private static String followCVSformat(String value) {
        String result = value;
        if (result.contains("\"")) {
            result = result.replace("\"", "\"\"");
        }
        return result;

    }

    private static void writeLineCSV(Writer w, List<String> values){
        try {
            writeLine(w, values, ',', ' ');
        }catch(IOException ioe){
            System.err.println(String.format("Error writing to CSV: %s",
                    values.toString()));
            ioe.printStackTrace();
        }
    }

    private static void writeLine(Writer w, List<String> values,
                                  char separators, char customQuote)
            throws IOException {
        boolean first = true;
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!first) {
                sb.append(separators);
            }
            sb.append(followCVSformat(value));
            first = false;
        }
        sb.append("\n");
        w.append(sb.toString());
    }

    /* -------------- Debugging helper functions -------------- */
    /**
     * Debugging function saving annotation cutouts from the big image**/
    public void saveAnnotationBoxes(ArrayList<BufferedImage> annotationImages){
        Path currentRelativePath = Paths.get("");
        String RELATIVE_PATH = String.format("%s/Data/",
                currentRelativePath.toAbsolutePath().toString());
        int i = 0;
        for(BufferedImage image : annotationImages) {
            String filename = String.format("annotation_box%d.jpg", i);
            try {
                ImageIO.write(image, "png", new File(RELATIVE_PATH + filename));
            } catch (IOException e) {
                System.err.println("IOException when writing image: ");
                e.printStackTrace();
            }
            i++;
        }
    }
}
