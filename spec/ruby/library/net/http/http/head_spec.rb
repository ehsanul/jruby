require File.expand_path('../../../../../spec_helper', __FILE__)
require 'net/http'
require File.expand_path('../fixtures/http_server', __FILE__)

describe "Net::HTTP#head" do
  before :each do
    NetHTTPSpecs.start_server
    @http = Net::HTTP.start("localhost", 3333)
  end

  after :each do
    @http.finish if @http.started?
    NetHTTPSpecs.stop_server
  end

  it "sends a HEAD request to the passed path and returns the response" do
    response = @http.head("/request")
    # HEAD requests have no responses
    response.body.should be_nil
  end

  it "returns a Net::HTTPResponse" do
    @http.head("/request").should be_kind_of(Net::HTTPResponse)
  end
end
