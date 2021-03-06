/*******************************************************************************
 * Copyright (c) 2009, 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.e4.ui.tests.application;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.IPresentationEngine;

public class HeadlessPhotoDemoTest extends HeadlessApplicationTest {

	@Override
	protected String getURI() {
		return "org.eclipse.e4.ui.tests/xmi/photo.e4xmi";
	}

	@Override
	protected MPart getFirstPart() {
		return (MPart) findElement("ThumbnailsView");
	}

	@Override
	protected MPart getSecondPart() {
		return (MPart) findElement("ExifView");
	}

	@Override
	protected IPresentationEngine createPresentationEngine(
			String renderingEngineURI) throws Exception {
		HeadlessContextPresentationEngine engine = (HeadlessContextPresentationEngine) super
				.createPresentationEngine(renderingEngineURI);
		engine.setCreateContributions(false);
		return engine;
	}

}
