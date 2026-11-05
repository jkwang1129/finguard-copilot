"""Real SDK client for the isolated process harness. Reports no capability or business content."""
import argparse
import asyncio
import json
import os
import sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.shared.exceptions import McpError

async def verify(analysis_id, expect_denied):
    parameters=StdioServerParameters(command=sys.executable,args=['-m','finguard_mcp_bridge','--analysis-id',analysis_id],
        env={'FINGUARD_MCP_CAPABILITY':os.environ.pop('FINGUARD_MCP_CAPABILITY')})
    async with stdio_client(parameters) as (read,write):
        async with ClientSession(read,write) as session:
            hello=await session.initialize()
            if expect_denied:
                try:await session.list_tools()
                except McpError:return {'initialized':True,'capabilityDenied':True,'protocolVersion':hello.protocolVersion}
                raise AssertionError('Expired capability unexpectedly listed tools')
            listed=await session.list_tools()
            assert {tool.name for tool in listed.tools}=={'get_review_facts','get_task_state','search_review_policy'}
            for name in ['get_review_facts','get_task_state']:
                result=await session.call_tool(name,{})
                assert not result.isError
                content=json.loads(result.content[0].text)
                assert content['toolName']==name and content['source']=='CORE_SNAPSHOT'
            exhausted=await session.call_tool('get_task_state',{})
            unknown=await session.call_tool('arbitrary_url',{})
            assert exhausted.isError and unknown.isError
            return {'initialized':True,'protocolVersion':hello.protocolVersion,'tools':[t.name for t in listed.tools],
                'successfulReads':2,'budgetRejected':True,'unknownToolRejected':True}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--analysis-id',required=True);parser.add_argument('--expect-denied',action='store_true')
    arguments=parser.parse_args()
    print(json.dumps(asyncio.run(verify(arguments.analysis_id,arguments.expect_denied))))
