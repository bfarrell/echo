/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.pipelinetriggers

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Counter
import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.echo.model.Event
import com.netflix.spinnaker.echo.model.Pipeline
import com.netflix.spinnaker.echo.pipelinetriggers.monitor.DockerEventMonitor
import com.netflix.spinnaker.echo.test.RetrofitStubs
import rx.functions.Action1
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class DockerEventMonitorSpec extends Specification implements RetrofitStubs {
  def objectMapper = new ObjectMapper()
  def pipelineCache = Mock(PipelineCache)
  def subscriber = Mock(Action1)
  def registry = Stub(Registry) {
    createId(*_) >> Stub(Id)
    counter(*_) >> Stub(Counter)
    gauge(*_) >> Integer.valueOf(1)
  }

  @Subject
  def monitor = new DockerEventMonitor(pipelineCache, subscriber, registry)

  @Unroll
  def "triggers pipelines for successful builds for #triggerType"() {
    given:
    def pipeline = createPipelineWith(trigger)
    pipelineCache.getPipelines() >> [pipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    1 * subscriber.call({
      it.application == pipeline.application && it.name == pipeline.name
    })

    where:
    event               | trigger              | triggerType
    createDockerEvent() | enabledDockerTrigger | 'docker'
  }

  def "attaches docker trigger to the pipeline"() {
    given:
    pipelineCache.getPipelines() >> [pipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    1 * subscriber.call({
      it.trigger.type == enabledDockerTrigger.type
      it.trigger.registry == enabledDockerTrigger.registry
      it.trigger.repository == enabledDockerTrigger.repository
      it.trigger.tag == enabledDockerTrigger.tag
    })

    where:
    event = createDockerEvent()
    pipeline = createPipelineWith(enabledJenkinsTrigger, nonJenkinsTrigger, enabledDockerTrigger, disabledDockerTrigger)
  }

  def "an event can trigger multiple pipelines"() {
    given:
    pipelineCache.getPipelines() >> pipelines

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    pipelines.size() * subscriber.call(_ as Pipeline)

    where:
    event = createDockerEvent()
    pipelines = (1..2).collect {
      Pipeline.builder()
        .application("application")
        .name("pipeline$it")
        .id("id")
        .triggers([enabledDockerTrigger])
        .build()
    }
  }

  @Unroll
  def "does not trigger #description pipelines"() {
    given:
    pipelineCache.getPipelines() >> [pipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    0 * subscriber._

    where:
    trigger               | description
    disabledDockerTrigger | "disabled docker trigger"
    nonJenkinsTrigger     | "non-Jenkins"

    pipeline = createPipelineWith(trigger)
    event = createDockerEvent()
  }

  @Unroll
  def "does not trigger #description pipelines for docker"() {
    given:
    pipelineCache.getPipelines() >> [pipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    0 * subscriber._

    where:
    trigger                                               | description
    disabledDockerTrigger                                 | "disabled docker trigger"
    enabledDockerTrigger.withRegistry("notRegistry")      | "different registry"
    enabledDockerTrigger.withRepository("notRepository")  | "different repository"

    pipeline = createPipelineWith(trigger)
    event = createDockerEvent()
  }


  @Unroll
  def "does not trigger a pipeline that has an enabled docker trigger with missing #field"() {
    given:
    pipelineCache.getPipelines() >> [badPipeline, goodPipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    1 * subscriber.call({ it.id == goodPipeline.id })

    where:
    trigger                                    | field
    enabledDockerTrigger.withRegistry(null)    | "registry"
    enabledDockerTrigger.withRepository(null) | "repository"
    enabledDockerTrigger.withTag(null)         | "tag"

    event = createDockerEvent()
    goodPipeline = createPipelineWith(enabledDockerTrigger)
    badPipeline = createPipelineWith(trigger)
  }

  @Unroll
  def "triggers a pipeline that has an enabled docker trigger with regex"() {
    given:
    def pipeline = createPipelineWith(trigger)
    pipelineCache.getPipelines() >> [pipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    1 * subscriber.call({ it.id == pipeline.id })

    where:
    trigger                               | field
    enabledDockerTrigger.withTag("\\d+")  | "regex tag"

    event = createDockerEvent("2")
  }

  @Unroll
  def "does not trigger a pipeline that has an enabled docker trigger with regex"() {
    given:
    def pipeline = createPipelineWith(trigger)
    pipelineCache.getPipelines() >> [pipeline]

    when:
    monitor.processEvent(objectMapper.convertValue(event, Event))

    then:
    0 * subscriber._

    where:
    trigger                               | field
    enabledDockerTrigger.withTag("\\d+")  | "regex tag"

    event = createDockerEvent()
  }
}
