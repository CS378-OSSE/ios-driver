/*
 * Copyright 2012-2013 eBay Software Foundation and ios-driver committers
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.uiautomation.ios.server.simulator;

import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.Response;
import org.uiautomation.ios.IOSCapabilities;
import org.uiautomation.ios.communication.device.DeviceType;
import org.uiautomation.ios.communication.device.DeviceVariation;
import org.uiautomation.ios.server.application.APPIOSApplication;
import org.uiautomation.ios.server.application.IOSRunningApplication;
import org.uiautomation.ios.server.command.UIAScriptRequest;
import org.uiautomation.ios.server.command.UIAScriptResponse;
import org.uiautomation.ios.server.instruments.IOSDeviceManager;
import org.uiautomation.ios.server.instruments.InstrumentsVersion;
import org.uiautomation.ios.server.instruments.communication.CommunicationChannel;
import org.uiautomation.ios.server.instruments.communication.curl.CURLBasedCommunicationChannel;
import org.uiautomation.ios.server.services.Instruments;
import org.uiautomation.ios.server.services.InstrumentsAppleScreenshotService;
import org.uiautomation.ios.server.services.TakeScreenshotService;
import org.uiautomation.ios.utils.ApplicationCrashListener;
import org.uiautomation.ios.utils.ClassicCommands;
import org.uiautomation.ios.utils.Command;
import org.uiautomation.ios.utils.ScriptHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.uiautomation.ios.server.instruments.communication.CommunicationMode.CURL;

public class InstrumentsApple implements Instruments {

  private static final Logger log = Logger.getLogger(InstrumentsApple.class.getName());
  private final String uuid;
  private final File template;
  private final IOSRunningApplication application;
  private final File output;
  private final String sessionId;
  private final List<String> envtParams;
  private final Command instruments;
  private final CURLBasedCommunicationChannel channel;
  private final InstrumentsVersion version;
  private final TakeScreenshotService screenshotService;
  private final ApplicationCrashListener listener;
  private final IOSDeviceManager deviceManager;
  private final IOSCapabilities caps;

  public InstrumentsApple(String uuid, InstrumentsVersion version, int port, String sessionId,
                          IOSRunningApplication application,
                          List<String> envtParams, String desiredSDKVersion,IOSCapabilities caps) {
    this.uuid = uuid;
    this.caps = caps;
    this.version = version;
    this.sessionId = sessionId;
    this.application = application;
    this.envtParams = envtParams;
    template = ClassicCommands.getAutomationTemplate();

    String appPath = application.getDotAppAbsolutePath();
    File scriptPath = new ScriptHelper().getScript(port, appPath, sessionId, CURL);
    output = createTmpOutputFolder();

    instruments = createInstrumentCommand(scriptPath);
    listener = new ApplicationCrashListener();
    instruments.registerListener(listener);
    instruments.setWorkingDirectory(output);

    channel = new CURLBasedCommunicationChannel(sessionId);

    screenshotService = new InstrumentsAppleScreenshotService(this, sessionId);
    deviceManager = new IOSSimulatorManager(caps,this);
  }

  public void start() throws InstrumentsFailedToStartException {
    DeviceType deviceType = caps.getDevice();
    DeviceVariation variation = caps.getDeviceVariation();
    String locale = caps.getLocale();
    String language = caps.getLanguage();

    deviceManager.setVariation(deviceType, variation);
    this.application.setDefaultDevice(deviceType);
    deviceManager.setSDKVersion();
    deviceManager.resetContentAndSettings();
    deviceManager.setL10N(locale, language);
    deviceManager.setKeyboardOptions();
    deviceManager.setLocationPreference(true);
    deviceManager.setMobileSafariOptions();

    instruments.start();

    log.fine("waiting for registration request");
    boolean success = false;
    try {
      success = channel.waitForUIScriptToBeStarted();
    } catch (InterruptedException e) {
      throw new InstrumentsFailedToStartException("instruments was interrupted while starting.");
    } finally {
      // appears only in ios6. : Automation Instrument ran into an exception
      // while trying to run the
      // script. UIAScriptAgentSignaledException
      if (!success) {
        instruments.forceStop();
        if (deviceManager!=null){
          deviceManager.cleanupDevice();
        }
        throw new InstrumentsFailedToStartException("Instruments crashed.");
      }
    }
  }

  @Override
  public void stop() {
    deviceManager.cleanupDevice();
    instruments.forceStop();
    channel.stop();
  }

  public void startWithDummyScript() {
    File script = new ScriptHelper().createTmpScript("UIALogger.logMessage('warming up');");
    Command c = createInstrumentCommand(script);
    c.executeAndWait();
  }

  private Command createInstrumentCommand(File script) {
    List<String> args = new ArrayList<String>();

    args.add(getInstrumentsClient());
    if (uuid != null) {
      args.add("-w");
      args.add(uuid);
    }
    args.add("-t");
    args.add(template.getAbsolutePath());
    args.add(application.getDotAppAbsolutePath());
    args.add("-e");
    args.add("UIASCRIPT");
    args.add(script.getAbsolutePath());
    args.add("-e");
    args.add("UIARESULTSPATH");
    args.add(output.getAbsolutePath());
    args.addAll(envtParams);
    return new Command(args, true);
  }

  private File createTmpOutputFolder() {
    try {
      File output = File.createTempFile(sessionId, null);
      output.delete();
      output.mkdir();
      output.deleteOnExit();
      return output;
    } catch (IOException e) {
      throw new WebDriverException(
          "Cannot create the tmp folder where all the instruments tmp files"
          + "will be stored.", e);
    }
  }

  private String getInstrumentsClient() {
    return InstrumentsNoDelayLoader.getInstance(version).getInstruments().getAbsolutePath();
  }

  @Override
  public Response executeCommand(UIAScriptRequest request) {
    UIAScriptResponse res = channel.executeCommand(request);
    return res.getResponse();
  }



  @Override
  public CommunicationChannel getChannel() {
    return channel;
  }

  @Override
  public TakeScreenshotService getScreenshotService() {
    return screenshotService;
  }

  public File getOutput(){
    return output;
  }




}