/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.sld.server;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.network.store.client.NetworkStoreService;
import com.powsybl.sld.GraphBuilder;
import com.powsybl.sld.NetworkGraphBuilder;
import com.powsybl.sld.VoltageLevelDiagram;
import com.powsybl.sld.layout.LayoutParameters;
import com.powsybl.sld.layout.SmartVoltageLevelLayoutFactory;
import com.powsybl.sld.layout.VoltageLevelLayoutFactory;
import com.powsybl.sld.library.ResourcesComponentLibrary;
import com.powsybl.sld.svg.DefaultDiagramInitialValueProvider;
import com.powsybl.sld.svg.DefaultDiagramStyleProvider;
import com.powsybl.sld.svg.DefaultNodeLabelConfiguration;
import com.powsybl.sld.svg.DefaultSVGWriter;
import com.powsybl.sld.util.NominalVoltageDiagramStyleProvider;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
@ComponentScan(basePackageClasses = {NetworkStoreService.class})
@Service
class SingleLineDiagramService {

    private static final ResourcesComponentLibrary COMPONENT_LIBRARY = new ResourcesComponentLibrary("/ConvergenceLibrary");

    private static final LayoutParameters LAYOUT_PARAMETERS = new LayoutParameters();

    @Autowired
    private NetworkStoreService networkStoreService;

    private Network getNetwork(UUID networkUuid) {
        try {
            return networkStoreService.getNetwork(networkUuid);
        } catch (PowsyblException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Network '" + networkUuid + "' not found");
        }
    }

    private static VoltageLevelDiagram createVoltageLevelDiagram(Network network, String voltageLevelId, boolean useName) {
        VoltageLevel voltageLevel = network.getVoltageLevel(voltageLevelId);
        if (voltageLevel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voltage level " + voltageLevelId + " not found");
        }
        VoltageLevelLayoutFactory voltageLevelLayoutFactory = new SmartVoltageLevelLayoutFactory(network);
        GraphBuilder graphBuilder = new NetworkGraphBuilder(network);
        return VoltageLevelDiagram.build(graphBuilder, voltageLevelId, voltageLevelLayoutFactory, useName, false);
    }

    Pair<String, String> generateSvgAndMetadata(UUID networkUuid, String voltageLevelId, boolean useName) {
        Network network = getNetwork(networkUuid);

        VoltageLevelDiagram voltageLevelDiagram = createVoltageLevelDiagram(network, voltageLevelId, useName);

        try (StringWriter svgWriter = new StringWriter();
             StringWriter metadataWriter = new StringWriter()) {

            DefaultSVGWriter defaultSVGWriter = new DefaultSVGWriter(COMPONENT_LIBRARY, LAYOUT_PARAMETERS);
            DefaultDiagramInitialValueProvider defaultDiagramInitialValueProvider = new DefaultDiagramInitialValueProvider(network);
            DefaultDiagramStyleProvider defaultDiagramStyleProvider = new NominalVoltageDiagramStyleProvider();
            DefaultNodeLabelConfiguration defaultNodeLabelConfiguration = new DefaultNodeLabelConfiguration(COMPONENT_LIBRARY);

            voltageLevelDiagram.writeSvg("",
                                         defaultSVGWriter,
                                         defaultDiagramInitialValueProvider,
                                         defaultDiagramStyleProvider,
                                         defaultNodeLabelConfiguration,
                                         svgWriter,
                                         metadataWriter);

            return Pair.of(svgWriter.toString(), metadataWriter.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    byte[] generateSvgAndMetadataZip(UUID networkUuid, String voltageLevelId, boolean useName) {
        Pair<String, String> svgAndMetadata = generateSvgAndMetadata(networkUuid, voltageLevelId, useName);

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(byteArrayOutputStream))) {

            zipOutputStream.putNextEntry(new ZipEntry(voltageLevelId + ".svg"));
            zipOutputStream.write(svgAndMetadata.getLeft().getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();

            zipOutputStream.putNextEntry(new ZipEntry(voltageLevelId + ".json"));
            zipOutputStream.write(svgAndMetadata.getRight().getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();

            zipOutputStream.finish();
            zipOutputStream.flush();

            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
