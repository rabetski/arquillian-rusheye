package org.jboss.rusheye.arquillian.observer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.apache.commons.lang.ArrayUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.rusheye.RushEye;
import org.jboss.rusheye.arquillian.configuration.RusheyeConfiguration;
import org.jboss.rusheye.arquillian.event.CrawlEvent;
import org.jboss.rusheye.parser.listener.CompareListener;
import org.jboss.rusheye.result.collector.ResultCollectorImpl;
import org.jboss.rusheye.result.statistics.OverallStatistics;
import org.jboss.rusheye.result.storage.FileStorage;
import org.jboss.rusheye.result.writer.FileResultWriter;
import org.jboss.rusheye.retriever.mask.MaskFileRetriever;
import org.jboss.rusheye.retriever.pattern.PatternFileRetriever;
import org.jboss.rusheye.retriever.sample.FileSampleRetriever;
import org.jboss.rusheye.suite.MaskType;

import static org.apache.commons.lang.StringUtils.split;
import static org.apache.commons.lang.StringUtils.substringAfter;
import static org.apache.commons.lang.StringUtils.substringAfterLast;
import static org.apache.commons.lang.StringUtils.substringBeforeLast;

/**
 *
 * @author <a href="mailto:jhuska@redhat.com">Juraj Huska</a>
 */
public class CrawlObserver {

    @Inject
    private Instance<RusheyeConfiguration> rusheyeConfiguration;

    private Document document;
    private Namespace ns;

    public void crawl(@Observes CrawlEvent event) {
        document = DocumentHelper.createDocument();
        addDocumentRoot();
        writeDocument();
    }

    private void writeDocument() {
        OutputFormat format = OutputFormat.createPrettyPrint();
        OutputStream out = openOutputStream();

        try {
            XMLWriter writer = new XMLWriter(out, format);
            writer.write(document);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            printErrorMessage(e);
            System.exit(7);
        }
    }

    private OutputStream openOutputStream() {
        if (rusheyeConfiguration.get().getOutput() == null) {
            return System.out;
        }

        try {
            return new FileOutputStream(rusheyeConfiguration.get().getOutput());
        } catch (IOException e) {
            printErrorMessage(e);
            System.exit(7);
            return null;
        }
    }

    private void addDocumentRoot() {
        ns = Namespace.get(RushEye.NAMESPACE_VISUAL_SUITE);

        Element root = document.addElement(QName.get("visual-suite", ns));

        Namespace xsi = Namespace.get("xsi", "http://www.w3.org/2001/XMLSchema-instance");
        QName schemaLocation = QName.get("schemaLocation", xsi);

        root.addNamespace("", ns.getURI());
        root.addNamespace(xsi.getPrefix(), xsi.getURI());
        root.addAttribute(schemaLocation, ns.getURI() + " " + RushEye.SCHEMA_LOCATION_VISUAL_SUITE);

        Element globalConfiguration = root.addElement(QName.get("global-configuration", ns));
        addSuiteListener(globalConfiguration);
        addRetrievers(globalConfiguration);
        addPerception(globalConfiguration);
        addMasksByType(rusheyeConfiguration.get().getMaskBase(), globalConfiguration);
        addTests(rusheyeConfiguration.get().getPatternBase(), root);
    }

    private void addSuiteListener(Element globalConfiguration) {
        Element suiteListener = globalConfiguration.addElement(QName.get("listener", ns));
        suiteListener.addAttribute("type", CompareListener.class.getName());
        suiteListener.addElement(QName.get("result-collector", ns)).addText(ResultCollectorImpl.class.getName());
        suiteListener.addElement(QName.get("result-storage", ns)).addText(FileStorage.class.getName());
        suiteListener.addElement(QName.get("result-writer", ns)).addText(FileResultWriter.class.getName());
        suiteListener.addElement(QName.get("result-statistics", ns)).addText(OverallStatistics.class.getName());
    }

    private void addRetrievers(Element globalConfiguration) {
        globalConfiguration.addElement(QName.get("pattern-retriever", ns)).addAttribute("type", PatternFileRetriever.class.getName());
        globalConfiguration.addElement(QName.get("mask-retriever", ns)).addAttribute("type", MaskFileRetriever.class.getName());
        globalConfiguration.addElement(QName.get("sample-retriever", ns)).addAttribute("type",
                FileSampleRetriever.class.getName());
    }

    private void addPerception(Element base) {
        Element perception = base.addElement(QName.get("perception", ns));

        RusheyeConfiguration conf = rusheyeConfiguration.get();

        if (conf.getOnePixelTreshold() != null) {
            perception.addElement(QName.get("one-pixel-treshold", ns)).addText(rusheyeConfiguration.get().getOnePixelTreshold());
        }
        if (conf.getGlobalDifferenceTreshold() != null) {
            perception.addElement(QName.get("global-difference-treshold", ns))
                    .addText(conf.getGlobalDifferenceTreshold());
        }
        if (conf.getGlobalDifferenceAmount() != null) {
            perception.addElement(QName.get("global-difference-amount", ns)).addText(conf.getGlobalDifferenceAmount());
        }
    }

    private void addMasksByType(File dir, Element base) {
        for (MaskType maskType : MaskType.values()) {
            File maskDir = new File(dir, "masks-" + maskType.value());

            if (maskDir.exists() && maskDir.isDirectory() && maskDir.listFiles().length > 0) {
                addMasks(maskDir, base, maskType);
            }
        }
    }

    private void addMasks(File dir, Element base, MaskType maskType) {
        if (dir.exists() && dir.isDirectory()) {
            for (File file : dir.listFiles()) {
                String id = substringBeforeLast(file.getName(), ".");
                String source = getRelativePath(rusheyeConfiguration.get().getMaskBase(), file);
                String info = substringAfterLast(id, "--");
                String[] infoTokens = split(info, "-");

                Element mask = base.addElement(QName.get("mask", ns)).addAttribute("id", id)
                        .addAttribute("type", maskType.value()).addAttribute("source", source);

                for (String alignment : infoTokens) {
                    String attribute = ArrayUtils.contains(new String[]{"top", "bottom"}, alignment) ? "vertical-align"
                            : "horizontal-align";
                    mask.addAttribute(attribute, alignment);
                }
            }
        }
    }

    private void addTests(File dir, Element root) {
        if (dir.exists() && dir.isDirectory()) {
            tests:
            for (File testFile : dir.listFiles()) {
                for (MaskType mask : MaskType.values()) {
                    if (testFile.getName().equals("masks-" + mask.value())) {
                        continue tests;
                    }
                }
//                if (testFile.isDirectory() && testFile.listFiles().length > 0) {
//                    String Tname = testFile.getName();
//
//                    Element test = root.addElement(QName.get("test", ns));
//                    test.addAttribute("name", name);
//
//                    addPatterns(testFile, test);
//                    addMasksByType(testFile, test);
//                }
                if (testFile.isDirectory()) {
                    recursiveFindTestName(testFile, root);
                }

            }
        }
    }

    private void recursiveFindTestName(File dir, Element root) {
        for (File testFile : dir.listFiles()) {
            if (testFile.isFile()) {
                
                String patterName = substringBeforeLast(testFile.getName(), ".");

                Element test = root.addElement(QName.get("test", ns));
                String testName = testFile.getParentFile().getParentFile().getName() + 
                        "." + testFile.getParentFile().getName();
                test.addAttribute("name", testName);

                String source = getRelativePath(rusheyeConfiguration.get().getPatternBase(), testFile);

                Element pattern = test.addElement(QName.get("pattern", ns));
                pattern.addAttribute("name", patterName);
                pattern.addAttribute("source", source);
            } else if (testFile.isDirectory()) {
                recursiveFindTestName(testFile, root);
            }
        }
    }

    private void addPatterns(File dir, Element test) {
        if (dir.exists() && dir.isDirectory()) {
            for (File file : dir.listFiles()) {
                if (file.isFile()) {
                    String name = substringBeforeLast(file.getName(), ".");
                    String source = getRelativePath(rusheyeConfiguration.get().getPatternBase(), file);

                    Element pattern = test.addElement(QName.get("pattern", ns));
                    pattern.addAttribute("name", name);
                    pattern.addAttribute("source", source);
                }
            }
        }
    }

    private String getRelativePath(File base, File file) {
        return substringAfter(file.getPath(), base.getPath()).replaceFirst("^/", "");
    }

    private void printErrorMessage(Exception e) {
        System.err.println(e.getMessage());
    }
}